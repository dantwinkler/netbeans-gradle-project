package org.netbeans.gradle.project.query;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
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
import java.util.prefs.PreferenceChangeEvent;
import java.util.prefs.PreferenceChangeListener;
import javax.swing.SwingUtilities;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.properties.GradleOptionsPanelController;
import org.netbeans.spi.java.classpath.ClassPathFactory;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.PathResourceImplementation;
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

    private List<File> getGradleBinaries() {
        String gradleHome = GradleOptionsPanelController.getGradleHome();
        if (gradleHome.isEmpty()) {
            return Collections.emptyList();
        }

        File gradleHomeDir = new File(gradleHome);
        if (!gradleHomeDir.isDirectory()) {
            return Collections.emptyList();
        }

        File binDir = new File(gradleHomeDir, "lib");
        if (!binDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] jars = binDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lowerCaseName = name.toLowerCase(Locale.US);
                return lowerCaseName.startsWith("gradle-") && lowerCaseName.endsWith(".jar");
            }
        });

        return Arrays.asList(jars);
    }

    private void updateClassPathResources() {
        List<File> jars = getGradleBinaries();
        LOGGER.log(Level.FINE,
                "Updating the .gradle file classpaths to: {0}",
                jars);

        @SuppressWarnings("unchecked")
        List<PathResourceImplementation> jarResources = GradleClassPathProvider.getPathResources(jars);

        // ClassPathType.BOOT is never read from the classpathResources
        classpathResources.put(ClassPathType.COMPILE, jarResources);
        classpathResources.put(ClassPathType.RUNTIME, jarResources);
    }

    private void setupClassPaths() {
        JavaPlatform defaultJdk = JavaPlatform.getDefault();

        if (defaultJdk != null) {
            // This can never change, so don't bother with classpathResources
            classpaths.put(ClassPathType.BOOT, defaultJdk.getBootstrapLibraries());
        }
        else {
            LOGGER.warning("There is no default JDK.");
        }

        updateClassPathResources();

        classpaths.put(ClassPathType.COMPILE, createClassPath(ClassPathType.COMPILE));
        classpaths.put(ClassPathType.RUNTIME, createClassPath(ClassPathType.RUNTIME));
    }

    private void init() {
        GradleOptionsPanelController.addSettingsChangeListener(new PreferenceChangeListener() {
            @Override
            public void preferenceChange(PreferenceChangeEvent evt) {
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
        });

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
