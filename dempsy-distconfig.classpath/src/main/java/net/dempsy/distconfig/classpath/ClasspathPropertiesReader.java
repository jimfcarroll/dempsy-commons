/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dempsy.distconfig.classpath;

import static net.dempsy.util.Functional.chainThrows;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dempsy.distconfig.PropertiesReader;
import net.dempsy.distconfig.PropertiesWatcher;
import net.dempsy.util.SafeString;

public class ClasspathPropertiesReader implements PropertiesReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClasspathPropertiesReader.class);

    private final boolean actLikeYouSupportNotifications;
    private final String classpathUri;

    public ClasspathPropertiesReader(final String classpathUri, final boolean actLikeYouSupportNotifications) throws IOException {
        this.actLikeYouSupportNotifications = actLikeYouSupportNotifications;
        this.classpathUri = classpathUri;
    }

    public ClasspathPropertiesReader(final String classpathUri) throws IOException {
        this(classpathUri, false);
    }

    private static String cleanPath(final String path) {
        String trimmed = path.trim();
        if(trimmed.startsWith("/"))
            trimmed = cleanPath(trimmed.substring(1, trimmed.length()));
        if(trimmed.endsWith("/"))
            trimmed = trimmed.substring(0, trimmed.length() - 1); // chop off trailing '/'. Let's hope there's not more than one.
        return trimmed;
    }

    @Override
    public VersionedProperties read(final PropertiesWatcher watcher) throws IOException {
        if(watcher != null && !actLikeYouSupportNotifications)
            throw new UnsupportedOperationException(
                "Cannot add watch \"" + SafeString.objectDescription(watcher) + " to a " + ClasspathPropertiesReader.class.getSimpleName());

        final String resource = (classpathUri.startsWith("classpath:///")) ? classpathUri.substring(13)
            : (classpathUri.startsWith("classpath:") ? classpathUri.substring(10) : cleanPath(classpathUri));

        LOGGER.debug("trying to read:{}", resource);
        final InputStream resAsStream = ClasspathPropertiesReader.class.getClassLoader().getResourceAsStream(resource);
        return resAsStream == null ? new VersionedProperties(-1, null)
            : new VersionedProperties(0,
                chainThrows(new Properties(), p -> p.load(resAsStream)));
    }

    @Override
    public boolean supportsNotification() {
        return actLikeYouSupportNotifications;
    }
}
