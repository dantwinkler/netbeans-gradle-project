package org.netbeans.gradle.project.query;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.properties.GlobalGradleSettings;
import org.netbeans.spi.java.classpath.ClassPathFactory;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.PathResourceImplementation;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;
import org.openide.util.lookup.ServiceProviders;

@ServiceProviders({@ServiceProvider(service = ClassPathProvider.class)})
public final class GradleFilesClassPathProvider implements ClassPathProvider {
    private static final Logger LOGGER = Logger.getLogger(GradleFilesClassPathProvider.class.getName());

    private final AtomicBoolean initialized;
    private final SimpleSignal initSignal;
    private final ConcurrentMap<ClassPathType, List<PathResourceImplementation>> classpathResources;
    private final Map<ClassPathType, ClassPath> classpaths;

    private final PropertyChangeSupport changes;

    @SuppressWarnings("MapReplaceableByEnumMap") // no, it's not.
    public GradleFilesClassPathProvider() {
        this.initialized = new AtomicBoolean(false);
        this.initSignal = new SimpleSignal();
        this.classpaths = new EnumMap<ClassPathType, ClassPath>(ClassPathType.class);
        this.classpathResources = new ConcurrentHashMap<ClassPathType, List<PathResourceImplementation>>();

        this.changes = new PropertyChangeSupport(this);
    }

    public static boolean isGradleFile(FileObject file) {
        // case-insensitive check, so that there is no surprise on Windows.
        return "gradle".equals(file.getExt().toLowerCase(Locale.US));
    }

    // These PropertyChangeListener methods are declared because
    // for some reason, NetBeans want to use them through reflection.
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }

    private ClassPath createClassPath(ClassPathType classPathType) {
        return ClassPathFactory.createClassPath(new GradleClassPaths(classPathType));
    }

    private static URL[] getGradleBinaries() {
        FileObject gradleHome = GlobalGradleSettings.getGradleHome().getValue();
        if (gradleHome == null) {
            return new URL[0];
        }

        return GradleHomeClassPathProvider.getGradleBinaries(gradleHome);
    }

    private void updateClassPathResources() {
        URL[] jars = getGradleBinaries();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE,
                    "Updating the .gradle file classpaths to: {0}",
                    Arrays.toString(jars));
        }

        List<PathResourceImplementation> jarResources = new ArrayList<PathResourceImplementation>(jars.length);
        for (URL jar: jars) {
            jarResources.add(ClassPathSupport.createResource(jar));
        }

        classpathResources.put(ClassPathType.COMPILE, jarResources);
        classpathResources.put(ClassPathType.RUNTIME, jarResources);

        JavaPlatform platform = GlobalGradleSettings.getGradleJdk().getValue();
        if (platform != null) {
            List<PathResourceImplementation> platformResources = new LinkedList<PathResourceImplementation>();
            for (ClassPath.Entry entry: platform.getBootstrapLibraries().entries()) {
                platformResources.add(ClassPathSupport.createResource(entry.getURL()));
            }
            classpathResources.put(ClassPathType.BOOT, platformResources);
        }
    }

    private void setupClassPaths() {
        updateClassPathResources();

        classpaths.put(ClassPathType.BOOT, createClassPath(ClassPathType.BOOT));
        classpaths.put(ClassPathType.COMPILE, createClassPath(ClassPathType.COMPILE));
        classpaths.put(ClassPathType.RUNTIME, createClassPath(ClassPathType.RUNTIME));
    }

    private void init() {
        ChangeListener changeListener = new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                NbGradleProject.PROJECT_PROCESSOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        updateClassPathResources();
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                changes.firePropertyChange(ClassPathImplementation.PROP_RESOURCES, null, null);
                            }
                        });
                    }
                });
            }
        };
        GlobalGradleSettings.getGradleHome().addChangeListener(changeListener);
        GlobalGradleSettings.getGradleJdk().addChangeListener(changeListener);

        setupClassPaths();
    }

    @Override
    public ClassPath findClassPath(FileObject file, String type) {
        // case-insensitive check, so that there is no surprise on Windows.
        if (!isGradleFile(file)) {
            return null;
        }

        if (initialized.compareAndSet(false, true)) {
            try {
                init();
            } finally {
                initSignal.signal();
            }
        }
        else {
            if (!initSignal.tryWaitForSignal()) {
                // The current thread has been interrupted (shutdown?),
                // returning null is the best we can do.
                return null;
            }
        }

        ClassPathType classPathType = getClassPathType(type);
        if (classPathType == null) {
            return null;
        }

        ClassPath classpath = classpaths.get(classPathType);
        if (classpath != null) {
            return classpath;
        }

        setupClassPaths();
        return classpaths.get(classPathType);
    }

    private static ClassPathType getClassPathType(String type) {
        if (ClassPath.SOURCE.equals(type)) {
            return null;
        }
        else if (ClassPath.BOOT.equals(type)) {
            return ClassPathType.BOOT;
        }
        else if (ClassPath.COMPILE.equals(type)) {
            return ClassPathType.COMPILE;
        }
        else if (ClassPath.EXECUTE.equals(type)) {
            return ClassPathType.RUNTIME;
        }
        else if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
            return ClassPathType.COMPILE;
        }

        return null;
    }

    private class GradleClassPaths implements ClassPathImplementation {
        private final ClassPathType classPathType;

        public GradleClassPaths(ClassPathType classPathType) {
            assert classPathType != null;
            this.classPathType = classPathType;
        }

        @Override
        public List<PathResourceImplementation> getResources() {
            List<PathResourceImplementation> result = classpathResources.get(classPathType);
            return result != null
                    ? result
                    : Collections.<PathResourceImplementation>emptyList();
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            changes.addPropertyChangeListener(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            changes.removePropertyChangeListener(listener);
        }
    }

    private static class SimpleSignal {
        private final Lock lock;
        private final Condition signalEvent;
        private volatile boolean signaled;

        public SimpleSignal() {
            this.lock = new ReentrantLock();
            this.signalEvent = this.lock.newCondition();
            this.signaled = false;
        }

        public void signal() {
            lock.lock();
            try {
                signaled = true;
                signalEvent.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public boolean tryWaitForSignal() {
            if (signaled) {
                return true;
            }

            lock.lock();
            try {
                while (!signaled) {
                    signalEvent.await();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }

            return signaled;
        }
    }

    private enum ClassPathType {
        BOOT,
        COMPILE,
        RUNTIME
    }
}
