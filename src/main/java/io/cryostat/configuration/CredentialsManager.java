/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.configuration;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.script.ScriptException;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.Credentials;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.messaging.notifications.NotificationFactory;
import io.cryostat.platform.PlatformClient;
import io.cryostat.platform.ServiceRef;
import io.cryostat.rules.MatchExpressionEvaluator;
import io.cryostat.rules.MatchExpressionValidationException;
import io.cryostat.rules.MatchExpressionValidator;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;

public class CredentialsManager {

    private final Path credentialsDir;
    private final MatchExpressionValidator matchExpressionValidator;
    private final MatchExpressionEvaluator matchExpressionEvaluator;
    private final FileSystem fs;
    private final PlatformClient platformClient;
    private final Gson gson;
    private final Logger logger;

    private int nextId = 0;

    CredentialsManager(
            Path credentialsDir,
            MatchExpressionValidator matchExpressionValidator,
            MatchExpressionEvaluator matchExpressionEvaluator,
            FileSystem fs,
            PlatformClient platformClient,
            NotificationFactory notificationFactory,
            Gson gson,
            Logger logger) {
        this.credentialsDir = credentialsDir;
        this.matchExpressionValidator = matchExpressionValidator;
        this.matchExpressionEvaluator = matchExpressionEvaluator;
        this.fs = fs;
        this.platformClient = platformClient;
        this.gson = gson;
        this.logger = logger;
    }

    public void migrate() throws Exception {
        for (String file : this.fs.listDirectoryChildren(credentialsDir)) {
            BufferedReader reader;
            try {
                Path path = credentialsDir.resolve(file);
                reader = fs.readFile(path);
                TargetSpecificStoredCredentials targetSpecificCredential =
                        gson.fromJson(reader, TargetSpecificStoredCredentials.class);

                String targetId = targetSpecificCredential.getTargetId();
                if (StringUtils.isNotBlank(targetId)) {
                    addCredentials(
                            targetIdToMatchExpression(targetSpecificCredential.getTargetId()),
                            targetSpecificCredential.getCredentials());
                    fs.deleteIfExists(path);
                    logger.info("Migrated {}", path);
                }
            } catch (IOException e) {
                logger.warn(e);
                continue;
            }
        }
    }

    public static String targetIdToMatchExpression(String targetId) {
        if (StringUtils.isBlank(targetId)) {
            return null;
        }
        return String.format("target.connectUrl == \"%s\"", targetId);
    }

    public void load() throws IOException {
        this.nextId =
                this.fs.listDirectoryChildren(credentialsDir).stream()
                        .peek(n -> logger.trace("Credentials file: {}", n))
                        .map(credentialsDir::resolve)
                        .map(
                                path -> {
                                    try {
                                        String filename = path.getFileName().toString();
                                        return Integer.parseInt(filename);
                                    } catch (NumberFormatException nfe) {
                                        logger.error(nfe);
                                        try {
                                            fs.deleteIfExists(path);
                                        } catch (IOException ioe) {
                                            logger.error(ioe);
                                        }
                                        return 0;
                                    }
                                })
                        .reduce(Math::max)
                        .orElse(0);
    }

    public int addCredentials(String matchExpression, Credentials credentials)
            throws IOException, MatchExpressionValidationException {
        matchExpressionValidator.validate(matchExpression);
        Path destination = getPersistedPath(nextId);
        fs.writeString(
                destination,
                gson.toJson(new StoredCredentials(matchExpression, credentials)),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        fs.setPosixFilePermissions(
                destination,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return nextId++;
    }

    public int removeCredentials(String matchExpression)
            throws IOException, MatchExpressionValidationException {
        matchExpressionValidator.validate(matchExpression);
        for (String pathString : this.fs.listDirectoryChildren(credentialsDir)) {
            Path path = credentialsDir.resolve(pathString);
            try (BufferedReader br = fs.readFile(path)) {
                StoredCredentials sc = gson.fromJson(br, StoredCredentials.class);
                if (Objects.equals(matchExpression, sc.getMatchExpression())) {
                    Path filenamePath = path.getFileName();
                    if (filenamePath == null) {
                        throw new IllegalStateException(path.toString());
                    }
                    String filename = filenamePath.toString();
                    fs.deleteIfExists(path);
                    return Integer.parseInt(filename);
                }
            }
        }
        throw new FileNotFoundException();
    }

    public Credentials getCredentialsByTargetId(String targetId)
            throws JsonSyntaxException, JsonIOException, IOException, ScriptException {
        for (ServiceRef service : this.platformClient.listDiscoverableServices()) {
            if (Objects.equals(targetId, service.getServiceUri().toString())) {
                return getCredentials(service);
            }
        }
        return null;
    }

    public Credentials getCredentials(ServiceRef serviceRef)
            throws JsonSyntaxException, JsonIOException, IOException, ScriptException {
        for (String pathString : this.fs.listDirectoryChildren(credentialsDir)) {
            Path path = credentialsDir.resolve(pathString);
            try (BufferedReader br = fs.readFile(path)) {
                StoredCredentials sc = gson.fromJson(br, StoredCredentials.class);
                if (matchExpressionEvaluator.applies(sc.getMatchExpression(), serviceRef)) {
                    return sc.getCredentials();
                }
            }
        }
        return null;
    }

    public Collection<ServiceRef> getServiceRefsWithCredentials()
            throws JsonSyntaxException, JsonIOException, IOException, ScriptException {
        List<ServiceRef> result = new ArrayList<>();
        for (ServiceRef service : this.platformClient.listDiscoverableServices()) {
            Credentials credentials = getCredentials(service);
            if (credentials != null) {
                result.add(service);
            }
        }
        return result;
    }

    public String get(int id) throws IOException {
        Path path = credentialsDir.resolve(String.valueOf(id));
        if (!fs.isRegularFile(path)) {
            throw new FileNotFoundException();
        }
        try (BufferedReader br = fs.readFile(path)) {
            StoredCredentials sc = gson.fromJson(br, StoredCredentials.class);
            return sc.getMatchExpression();
        }
    }

    public Set<ServiceRef> resolveMatchingTargets(int id) throws IOException {
        String matchExpression = get(id);
        Set<ServiceRef> matchedTargets = new HashSet<>();
        for (ServiceRef target : platformClient.listDiscoverableServices()) {
            try {
                if (matchExpressionEvaluator.applies(matchExpression, target)) {
                    matchedTargets.add(target);
                }
            } catch (ScriptException e) {
                logger.error(e);
                break;
            }
        }
        return matchedTargets;
    }

    public void delete(int id) throws IOException {
        Path path = credentialsDir.resolve(String.valueOf(id));
        if (!fs.isRegularFile(path)) {
            throw new FileNotFoundException();
        }
        fs.deleteIfExists(path);
    }

    public Map<Integer, String> getAll()
            throws JsonSyntaxException, JsonIOException, NumberFormatException, IOException {
        Map<Integer, String> result = new HashMap<>();

        for (String pathString : this.fs.listDirectoryChildren(credentialsDir)) {
            Path path = credentialsDir.resolve(pathString);
            Path filenamePath = path.getFileName();
            if (filenamePath == null) {
                continue;
            }
            try (BufferedReader br = fs.readFile(path)) {
                StoredCredentials sc = gson.fromJson(br, StoredCredentials.class);
                result.put(Integer.valueOf(filenamePath.toString()), sc.getMatchExpression());
            }
        }

        return result;
    }

    private Path getPersistedPath(int id) {
        return credentialsDir.resolve(String.valueOf(id));
    }

    public static class MatchedCredentials {
        private final String matchExpression;
        private final Collection<ServiceRef> targets;

        public MatchedCredentials(String matchExpression, Collection<ServiceRef> targets) {
            this.matchExpression = matchExpression;
            this.targets = new HashSet<>(targets);
        }

        public String getMatchExpression() {
            return matchExpression;
        }

        public Collection<ServiceRef> getTargets() {
            return Collections.unmodifiableCollection(targets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchExpression, targets);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            MatchedCredentials other = (MatchedCredentials) obj;
            return Objects.equals(matchExpression, other.matchExpression)
                    && Objects.equals(targets, other.targets);
        }
    }

    static class StoredCredentials {
        private final String matchExpression;
        private final Credentials credentials;

        StoredCredentials(String matchExpression, Credentials credentials) {
            this.matchExpression = matchExpression;
            this.credentials = credentials;
        }

        String getMatchExpression() {
            return this.matchExpression;
        }

        Credentials getCredentials() {
            return this.credentials;
        }

        @Override
        public int hashCode() {
            return Objects.hash(credentials, matchExpression);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            StoredCredentials other = (StoredCredentials) obj;
            return Objects.equals(credentials, other.credentials)
                    && Objects.equals(matchExpression, other.matchExpression);
        }
    }

    @Deprecated(since = "2.2", forRemoval = true)
    static class TargetSpecificStoredCredentials {
        private final String targetId;
        private final Credentials credentials;

        TargetSpecificStoredCredentials(String targetId, Credentials credentials) {
            this.targetId = targetId;
            this.credentials = credentials;
        }

        String getTargetId() {
            return this.targetId;
        }

        Credentials getCredentials() {
            return this.credentials;
        }

        @Override
        public int hashCode() {
            return Objects.hash(credentials, targetId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TargetSpecificStoredCredentials other = (TargetSpecificStoredCredentials) obj;
            return Objects.equals(credentials, other.credentials)
                    && Objects.equals(targetId, other.targetId);
        }
    }
}
