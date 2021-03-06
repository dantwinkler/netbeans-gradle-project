package org.netbeans.gradle.project.newproject;

import java.awt.Color;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.StringUtils;
import org.netbeans.gradle.project.validate.GroupValidator;
import org.netbeans.gradle.project.validate.InputCollector;
import org.netbeans.gradle.project.validate.Problem;
import org.netbeans.gradle.project.validate.Validator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbPreferences;
import org.openide.util.Utilities;

public final class NewProjectUtils {
    public static final Charset DEFAULT_FILE_ENCODING = Charset.forName("UTF-8");

    private static final String DEFAULT_PROJECTDIR_SETTINGS_KEY = "default-project-dir";
    private static final Pattern LEGAL_FILENAME_PATTERN = Pattern.compile("[^/./\\:*?\"<>|]*");
    private static final Pattern RECOMMENDED_PROJECTNAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-.]*");
    private static final Pattern MAVEN_GROUP_ID_PATTERN = Pattern.compile("[^/./\\:*?\"<>|]+");
    private static final Pattern MAVEN_VERSION_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-.]+");

    public static Preferences getPreferences() {
        return NbPreferences.forModule(NewProjectUtils.class);
    }

    public static String getDefaultProjectDir() {
        String defaultDir = NewProjectUtils.getPreferences().get(DEFAULT_PROJECTDIR_SETTINGS_KEY, "");
        if (defaultDir.isEmpty()) {
            defaultDir = new File(System.getProperty("user.home"), "Projects").getAbsolutePath();
        }
        return defaultDir;
    }

    public static void setDefaultProjectDir(String newValue) {
        getPreferences().put(DEFAULT_PROJECTDIR_SETTINGS_KEY, newValue.trim());
    }

    public static void createDefaultSourceDirs(FileObject projectDir) throws IOException {
        FileObject srcDir = projectDir.createFolder("src");
        FileObject mainDir = srcDir.createFolder("main");
        FileObject testDir = srcDir.createFolder("test");

        mainDir.createFolder("java");
        mainDir.createFolder("resources");

        testDir.createFolder("java");
        testDir.createFolder("resources");
    }

    public static void createMainClass(File projectDir, String mainClass) throws IOException {
        if (projectDir == null) throw new NullPointerException("projectDir");
        if (mainClass == null) throw new NullPointerException("mainClass");

        String packageName;
        String simpleClassName;

        int classSepIndex = mainClass.lastIndexOf('.');
        if (classSepIndex >= 0) {
            packageName = mainClass.substring(0, classSepIndex);
            simpleClassName = mainClass.substring(classSepIndex + 1);
        }
        else {
            packageName = "";
            simpleClassName = mainClass;
        }

        String relPackagePathStr = packageName.replace(".", File.separator);

        File packagePath = new File(projectDir, "src");
        packagePath = new File(packagePath, "main");
        packagePath = new File(packagePath, "java");
        if (!relPackagePathStr.isEmpty()) {
            packagePath = new File(packagePath, relPackagePathStr);
        }

        StringBuilder content = new StringBuilder(256);
        if (!packageName.isEmpty()) {
            content.append("package ");
            content.append(packageName);
            content.append(";\n\n");
        }

        content.append("public class ");
        content.append(simpleClassName);
        content.append(" {\n");
        content.append("    /**\n");
        content.append("     * @param args the command line arguments\n");
        content.append("     */\n");
        content.append("    public static void main(String[] args) {\n");
        content.append("    }\n");
        content.append("}\n");

        File mainClassPath = new File(packagePath, simpleClassName + ".java");

        FileUtil.createFolder(packagePath);
        StringUtils.writeStringToFile(content.toString(), DEFAULT_FILE_ENCODING, mainClassPath);
    }

    public static <InputType> Validator<InputType> merge(
            final Validator<? super InputType> validator1,
            final Validator<? super InputType> validator2) {

        return new Validator<InputType>() {
            @Override
            public Problem validateInput(InputType inputType) {
                Problem problem1 = validator1.validateInput(inputType);
                Problem problem2 = validator2.validateInput(inputType);

                if (problem1 == null) {
                    return problem2;
                }
                if (problem2 == null) {
                    return problem1;
                }

                return problem1.getLevel().getIntValue() >= problem2.getLevel().getIntValue()
                        ? problem1
                        : problem2;
            }
        };
    }

    public static Validator<String> createPatternValidator(
            final Pattern pattern,
            final Problem.Level severity,
            final String errorMessage) {
        if (pattern == null) throw new NullPointerException("pattern");
        if (severity == null) throw new NullPointerException("severity");
        if (errorMessage == null) throw new NullPointerException("errorMessage");

        return new Validator<String>() {
            @Override
            public Problem validateInput(String inputType) {
                if (!pattern.matcher(inputType).matches()) {
                    return new Problem(severity, errorMessage);
                }
                return null;
            }
        };
    }

    public static Validator<String> createProjectNameValidator() {
        Validator<String> patternValidators = merge(
                createPatternValidator(LEGAL_FILENAME_PATTERN,
                    Problem.Level.SEVERE,
                    NewProjectStrings.getIllegalProjectName()),
                createPatternValidator(RECOMMENDED_PROJECTNAME_PATTERN,
                    Problem.Level.WARNING,
                    NewProjectStrings.getNotRecommendedProjectName()));
        Validator<String> notEmptyValidator = new Validator<String>() {
            @Override
            public Problem validateInput(String inputType) {
                return inputType.isEmpty()
                        ? Problem.severe(NewProjectStrings.getProjectNameMustNotBeEmpty())
                        : null;
            }
        };

        return merge(notEmptyValidator, patternValidators);
    }

    public static Validator<String> createGroupIdValidator() {
        return createPatternValidator(
                MAVEN_GROUP_ID_PATTERN,
                Problem.Level.SEVERE,
                NewProjectStrings.getInvalidGroupId());
    }

    public static Validator<String> createVersionValidator() {
        return createPatternValidator(
                MAVEN_VERSION_PATTERN,
                Problem.Level.SEVERE,
                NewProjectStrings.getInvalidVersion());
    }

    public static Validator<String> createNewFolderValidator() {
        return new Validator<String>() {
            @Override
            public Problem validateInput(String inputType) {
                String projectDirStr = inputType.trim();
                if (projectDirStr.isEmpty()) {
                    return Problem.severe(NewProjectStrings.getInvalidPath());
                }

                File projectDir = FileUtil.normalizeFile(new File(projectDirStr));
                if (projectDir == null) {
                    return Problem.severe(NewProjectStrings.getInvalidPath());
                }

                // This check is required because checking these kind of paths
                // can be extremly slow.
                if (Utilities.isWindows() && projectDir.getAbsolutePath().startsWith("\\\\")) {
                    return Problem.severe(NewProjectStrings.getCannotCreateFolderHere());
                }

                if (projectDir.exists()) {
                    return Problem.severe(NewProjectStrings.getDirectoryAlreadyExists());
                }

                File rootPath = projectDir;
                while (rootPath != null && !rootPath.exists()) {
                    rootPath = rootPath.getParentFile();
                }

                if (rootPath == null || !rootPath.canWrite() || !rootPath.isDirectory()) {
                    return Problem.severe(NewProjectStrings.getCannotCreateFolderHere());
                }
                return null;
            }
        };
    }

    public static Validator<String> createVariableNameValidator() {
        return new Validator<String>() {
            @Override
            public Problem validateInput(String inputType) {
                if (inputType.isEmpty()) {
                    return Problem.severe(NewProjectStrings.getIllegalIdentifier());
                }

                if (!Character.isJavaIdentifierStart(inputType.charAt(0))) {
                    return Problem.severe(NewProjectStrings.getIllegalIdentifier());
                }

                for (int i = 1; i < inputType.length(); i++) {
                    if (!Character.isJavaIdentifierPart(inputType.charAt(i))) {
                        return Problem.severe(NewProjectStrings.getIllegalIdentifier());
                    }
                }
                return null;
            }
        };
    }

    public static Validator<String> createClassNameValidator(final boolean optional) {
        final Validator<String> varNameValidator = createVariableNameValidator();

        return new Validator<String>() {
            @Override
            public Problem validateInput(String inputType) {
                if (optional && inputType.isEmpty()) {
                    return null;
                }

                if (inputType.endsWith(".")) {
                    return Problem.severe(NewProjectStrings.getIllegalIdentifier());
                }

                String[] parts = inputType.split(Pattern.quote("."));
                for (String part: parts) {
                    Problem problem = varNameValidator.validateInput(part);
                    if (problem != null) {
                        assert problem.getLevel() == Problem.Level.SEVERE;
                        return problem;
                    }
                }
                if (parts.length == 1) {
                    return Problem.warning(NewProjectStrings.getShouldNotUseDefaultPackage());
                }

                return null;
            }
        };
    }

    public static InputCollector<String> createCollector(
            final JTextComponent component) {
        if (component == null) throw new NullPointerException("component");

        return new InputCollector<String>() {
            @Override
            public String getInput() {
                String result = component.getText();
                return result != null ? result.trim() : "";
            }
        };
    }

    public static void chooseProjectLocation(Component parent, JTextComponent jProjectEdit) {
        if (jProjectEdit == null) throw new NullPointerException("jProjectEdit");

        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(null);
        chooser.setDialogTitle(NbStrings.getSelectProjectLocationCaption());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        String path = jProjectEdit.getText();
        if (!path.isEmpty()) {
            File initialSelection = new File(path);
            if (initialSelection.exists()) {
                chooser.setSelectedFile(initialSelection);
            }
        }
        if (JFileChooser.APPROVE_OPTION == chooser.showOpenDialog(parent)) {
            File projectDir = chooser.getSelectedFile();
            jProjectEdit.setText(FileUtil.normalizeFile(projectDir).getAbsolutePath());
        }
    }

    public static void connectLabelToProblems(
            final BackgroundValidator validator,
            final JLabel jLabel) {
        if (validator == null) throw new NullPointerException("validator");
        if (jLabel == null) throw new NullPointerException("jLabel");

        jLabel.setText("");
        validator.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                Problem currentProblem = validator.getCurrentProblem();
                String message = currentProblem != null
                        ? currentProblem.getMessage()
                        : "";
                if (message.isEmpty()) {
                    jLabel.setText("");
                }
                else {
                    assert currentProblem != null;
                    String title;
                    Color labelColor;
                    switch (currentProblem.getLevel()) {
                        case INFO:
                            labelColor = Color.BLACK;
                            title = NbStrings.getInfoCaption();
                            break;
                        case WARNING:
                            labelColor = Color.ORANGE.darker();
                            title = NbStrings.getWarningCaption();
                            break;
                        case SEVERE:
                            labelColor = Color.RED;
                            title = NbStrings.getErrorCaption();
                            break;
                        default:
                            throw new AssertionError(currentProblem.getLevel().name());
                    }

                    jLabel.setForeground(labelColor);
                    jLabel.setText(title + ": " + message);
                }
            }
        });
    }

    public static void setupNewProjectValidators(
            final BackgroundValidator bckgValidator,
            final GroupValidator validators,
            final JTextComponent jProjectNameEdit,
            final JTextComponent jProjectFolderEdit,
            final JTextComponent jProjectLocationEdit) {
        if (validators == null) throw new NullPointerException("validators");
        if (jProjectNameEdit == null) throw new NullPointerException("jProjectNameEdit");
        if (jProjectFolderEdit == null) throw new NullPointerException("jProjectFolderEdit");
        if (jProjectLocationEdit == null) throw new NullPointerException("jProjectLocationEdit");

        validators.addValidator(
                NewProjectUtils.createProjectNameValidator(),
                NewProjectUtils.createCollector(jProjectNameEdit));
        validators.addValidator(
                NewProjectUtils.createNewFolderValidator(),
                NewProjectUtils.createCollector(jProjectFolderEdit));

        DocumentListener validationPerformer = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                bckgValidator.performValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                bckgValidator.performValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                bckgValidator.performValidation();
            }
        };
        DocumentListener projectFolderEditor = new DocumentListener() {
            private void updateFolderLocation() {
                File location = new File(
                        jProjectLocationEdit.getText().trim(),
                        jProjectNameEdit.getText().trim());
                jProjectFolderEdit.setText(location.getPath());
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                updateFolderLocation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateFolderLocation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateFolderLocation();
            }
        };
        jProjectNameEdit.getDocument().addDocumentListener(projectFolderEditor);
        jProjectLocationEdit.getDocument().addDocumentListener(projectFolderEditor);

        jProjectNameEdit.getDocument().addDocumentListener(validationPerformer);
        jProjectLocationEdit.getDocument().addDocumentListener(validationPerformer);
    }

    private NewProjectUtils() {
        throw new AssertionError();
    }
}
