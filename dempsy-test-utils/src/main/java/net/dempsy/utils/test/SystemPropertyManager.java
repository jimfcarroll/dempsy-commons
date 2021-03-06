package net.dempsy.utils.test;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

/**
 * This class allows the setting and then unsetting of System properties within the scope of a test.
 *
 * @deprecated this class has been moved to the dempsy-utils project as {@code net.dempsy.utils.SystemPropertyManager}
 *             since it has more useful situations than simply test scenarios. Specifically it can be used as part of
 *             a Spring based application's command line management.
 */
@Deprecated(forRemoval = true)
public class SystemPropertyManager implements AutoCloseable {
    private static class OldProperty {
        public final boolean hasOldValue;
        public final String oldValue;
        public final String name;

        public OldProperty(final String name, final boolean hasOldValue, final String oldValue) {
            this.hasOldValue = hasOldValue;
            this.oldValue = oldValue;
            this.name = name;
        }
    }

    private final List<OldProperty> oldProperties = new ArrayList<>();

    public SystemPropertyManager set(final String name, final String value) {
        oldProperties.add(new OldProperty(name, System.getProperties().containsKey(name), System.getProperty(name)));
        System.setProperty(name, value);
        return this;
    }

    public SystemPropertyManager setIfAbsent(final String name, final String value) {
        return System.getProperties().containsKey(name) ? this : set(name, value);
    }

    public SystemPropertyManager load(final String file) {
        final Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            props.entrySet().stream().forEach(e -> set((String)e.getKey(), (String)e.getValue()));
            return this;
        } catch(final IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Override
    public void close() {
        final int num = oldProperties.size();
        IntStream.range(0, num).forEach(i -> revert(oldProperties.get(num - i - 1)));
    }

    private static void revert(final OldProperty op) {
        if(op.hasOldValue)
            System.setProperty(op.name, op.oldValue);
        else
            System.clearProperty(op.name);
    }
}
