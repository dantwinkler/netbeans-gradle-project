package org.netbeans.gradle.project.properties;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import org.netbeans.api.java.platform.JavaPlatform;

public final class MemProjectProperties extends AbstractProjectProperties {
    private final MutableProperty<String> sourceLevel;
    private final MutableProperty<JavaPlatform> platform;
    private final MutableProperty<Charset> sourceEncoding;
    private final MutableProperty<List<PredefinedTask>> commonTasks;

    public MemProjectProperties() {
        JavaPlatform defaultPlatform = JavaPlatform.getDefault();
        this.sourceLevel = new DefaultMutableProperty<String>(getSourceLevelFromPlatform(defaultPlatform), false);
        this.platform = new DefaultMutableProperty<JavaPlatform>(defaultPlatform, false);
        this.sourceEncoding = new DefaultMutableProperty<Charset>(DEFAULT_SOURCE_ENCODING, false);
        this.commonTasks = new MutableListProperty<PredefinedTask>(Collections.<PredefinedTask>emptyList());
    }

    @Override
    public MutableProperty<String> getSourceLevel() {
        return sourceLevel;
    }

    @Override
    public MutableProperty<JavaPlatform> getPlatform() {
        return platform;
    }

    @Override
    public MutableProperty<Charset> getSourceEncoding() {
        return sourceEncoding;
    }

    @Override
    public MutableProperty<List<PredefinedTask>> getCommonTasks() {
        return commonTasks;
    }
}
