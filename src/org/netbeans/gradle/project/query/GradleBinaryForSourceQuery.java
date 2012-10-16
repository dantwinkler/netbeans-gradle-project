package org.netbeans.gradle.project.query;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.queries.BinaryForSourceQuery;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.ProjectInitListener;
import org.netbeans.gradle.project.model.NbDependencyGroup;
import org.netbeans.gradle.project.model.NbGradleModule;
import org.netbeans.gradle.project.model.NbModelUtils;
import org.netbeans.gradle.project.model.NbSourceType;
import org.netbeans.gradle.project.model.NbUriDependency;
import org.netbeans.spi.java.queries.BinaryForSourceQueryImplementation;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;
import org.openide.util.Utilities;

public final class GradleBinaryForSourceQuery
implements
        BinaryForSourceQueryImplementation,
        ProjectInitListener {
    private static final Logger LOGGER = Logger.getLogger(GradleSourceForBinaryQuery.class.getName());

    private static final URL[] NO_ROOTS = new URL[0];

    private final ConcurrentMap<FileObject, BinaryForSourceQuery.Result> cache;
    private final NbGradleProject project;
    private final ChangeSupport changes;

    public GradleBinaryForSourceQuery(NbGradleProject project) {
        if (project == null) throw new NullPointerException("project");
        this.project = project;
        this.cache = new ConcurrentHashMap<FileObject, BinaryForSourceQuery.Result>();

        EventSource eventSource = new EventSource();
        this.changes = new ChangeSupport(eventSource);
        eventSource.init(this.changes);
    }

    private static SourceType getSourceRootType(NbGradleModule module, FileObject root) {
        for (FileObject src: module.getSources(NbSourceType.SOURCE).getFileObjects()) {
            if (FileUtil.getRelativePath(src, root) != null) {
                return SourceType.NORMAL;
            }
        }
        for (FileObject src: module.getSources(NbSourceType.TEST_SOURCE).getFileObjects()) {
            if (FileUtil.getRelativePath(src, root) != null) {
                return SourceType.TEST;
            }
        }

        return SourceType.UNKNOWN;
    }

    private static URL[] getBinariesOfModule(NbGradleModule module) {
        File buildDir = module.getProperties().getOutput().getBuildDir();
        try {
            URL url = Utilities.toURI(buildDir).toURL();
            return new URL[]{url};
        } catch (MalformedURLException ex) {
            LOGGER.log(Level.INFO, "Cannot convert to URL: " + buildDir, ex);
        }

        return NO_ROOTS;
    }

    private static URL[] getTestBinariesOfModule(NbGradleModule module) {
        File testBuildDir = module.getProperties().getOutput().getTestBuildDir();
        try {
            URL url = Utilities.toURI(testBuildDir).toURL();
            return new URL[]{url};
        } catch (MalformedURLException ex) {
            LOGGER.log(Level.INFO, "Cannot convert to URL: " + testBuildDir, ex);
        }

        return NO_ROOTS;
    }

    private static URL[] getDependencyBinaries(NbGradleModule module, FileObject root) {
        for (NbDependencyGroup dependency: module.getDependencies().values()) {
            for (NbUriDependency uriDep: dependency.getUriDependencies()) {
                URI srcUri = uriDep.getSrcUri();
                if (srcUri != null) {
                    FileObject srcRoot = NbModelUtils.uriToFileObject(srcUri);
                    if (srcRoot != null && srcRoot.equals(root)) {
                        try {
                            return new URL[]{uriDep.getUri().toURL()};
                        } catch (MalformedURLException ex) {
                            // This should not happend but we cannot do anything
                            // in this case.
                            LOGGER.log(Level.SEVERE, "Cannot convert URI to URL: " + uriDep.getUri(), ex);
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static URL[] tryGetRoots(
            NbGradleModule module, FileObject root) {
        SourceType sourceType = getSourceRootType(module, root);
        switch (sourceType) {
            case NORMAL:
                return getBinariesOfModule(module);
            case TEST:
                return getTestBinariesOfModule(module);
            case UNKNOWN:
                return getDependencyBinaries(module, root);
            default:
                throw new AssertionError(sourceType.name());

        }
    }

    private void onModelChange() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                changes.fireChange();
            }
        });
    }

    @Override
    public void onInitProject() {
        project.addModelChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                onModelChange();
            }
        });
        // This is not called because it would trigger the loading of the
        // project even if it just shown in the project open dialog.
        // Although it should be called to ensure correct behaviour in every
        // case.
        // onModelChange();
    }

    @Override
    public BinaryForSourceQuery.Result findBinaryRoots(URL sourceRoot) {
        File sourceRootFile = FileUtil.archiveOrDirForURL(sourceRoot);
        if (sourceRootFile == null) {
            return null;
        }
        final FileObject sourceRootObj = FileUtil.toFileObject(sourceRootFile);
        if (sourceRootObj == null) {
            return null;
        }

        BinaryForSourceQuery.Result result = cache.get(sourceRootObj);
        if (result != null) {
            return result;
        }

        result = new BinaryForSourceQuery.Result() {
            @Override
            public URL[] getRoots() {
                NbGradleModule mainModule = project.getCurrentModel().getMainModule();

                URL[] roots = tryGetRoots(mainModule, sourceRootObj);
                if (roots != null) {
                    return roots;
                }

                for (NbGradleModule dependency: NbModelUtils.getAllModuleDependencies(mainModule)) {
                    URL[] depRoots = tryGetRoots(dependency, sourceRootObj);
                    if (depRoots != null) {
                        return depRoots;
                    }
                }

                return NO_ROOTS;
            }

            @Override
            public void addChangeListener(ChangeListener listener) {
                changes.addChangeListener(listener);
            }

            @Override
            public void removeChangeListener(ChangeListener listener) {
                changes.removeChangeListener(listener);
            }

            @Override
            public String toString() {
                return Arrays.toString(getRoots());
            }
        };
        BinaryForSourceQuery.Result prevResult = cache.putIfAbsent(sourceRootObj, result);
        return prevResult != null ? prevResult : result;
    }

    private enum SourceType {
        NORMAL,
        TEST,
        UNKNOWN
    }

    private static final class EventSource implements BinaryForSourceQuery.Result {
        private volatile ChangeSupport changes;

        public void init(ChangeSupport changes) {
            assert changes != null;
            this.changes = changes;
        }

        @Override
        public URL[] getRoots() {
            return NO_ROOTS;
        }

        @Override
        public void addChangeListener(ChangeListener l) {
            changes.addChangeListener(l);
        }

        @Override
        public void removeChangeListener(ChangeListener l) {
            changes.removeChangeListener(l);
        }
    }
}
