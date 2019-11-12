package net.corda.plugins;

import io.github.classgraph.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Console;

import javax.inject.Inject;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.StreamSupport;

import static java.util.Collections.*;
import static java.util.stream.Collectors.*;

@SuppressWarnings({"unused", "WeakerAccess"})
public class ScanApi extends DefaultTask {
    private static final int CLASS_MASK = Modifier.classModifiers();
    private static final int INTERFACE_MASK = Modifier.interfaceModifiers() & ~Modifier.ABSTRACT;
    /**
     * The VARARG modifier for methods has the same value as the TRANSIENT modifier for fields.
     * Unfortunately, {@link Modifier#methodModifiers() methodModifiers} doesn't include this
     * flag, and so we need to add it back ourselves.
     *
     * @link https://docs.oracle.com/javase/specs/jls/se8/html/index.html
     *
     * Also, the 0x1000 mask is the one used for methods that are generated by the compiler.
     * It's not publicly accessible at the moment, see: {@link Modifier#SYNTHETIC}
     */
    private static final int METHOD_MASK = Modifier.methodModifiers() | Modifier.TRANSIENT | 0x1000;
    private static final int FIELD_MASK = Modifier.fieldModifiers();
    private static final int VISIBILITY_MASK = Modifier.PUBLIC | Modifier.PROTECTED;

    private static final String ENUM_BASE_CLASS = "java.lang.Enum";
    private static final String DONOTIMPLEMENT_ANNOTATION_NAME = "net.corda.core.DoNotImplement";
    private static final String INTERNAL_ANNOTATION_NAME = ".CordaInternal";
    private static final String DEFAULT_INTERNAL_ANNOTATION = "net.corda.core" + INTERNAL_ANNOTATION_NAME;
    private static final Set<String> ANNOTATION_BLACKLIST;

    static {
        Set<String> blacklist = new LinkedHashSet<>();
        blacklist.add("kotlin.jvm.JvmField");
        blacklist.add("kotlin.jvm.JvmOverloads");
        blacklist.add("kotlin.jvm.JvmStatic");
        blacklist.add("kotlin.jvm.JvmDefault");
        blacklist.add("kotlin.Deprecated");
        blacklist.add("java.lang.Deprecated");
        blacklist.add(DEFAULT_INTERNAL_ANNOTATION);
        ANNOTATION_BLACKLIST = unmodifiableSet(blacklist);
    }

    /**
     * This information has been lifted from:
     *
     * @link <a href="https://github.com/JetBrains/kotlin/blob/master/core/descriptors.jvm/src/org/jetbrains/kotlin/load/kotlin/header/KotlinClassHeader.kt">KotlinClassHeader.Kind</a>
     * @link <a href="https://github.com/JetBrains/kotlin/blob/master/core/descriptors.jvm/src/org/jetbrains/kotlin/load/java/JvmAnnotationNames.java">JvmAnnotationNames</a>
     */
    private static final String KOTLIN_METADATA = "kotlin.Metadata";
    private static final String KOTLIN_CLASSTYPE_METHOD = "k";
    private static final int KOTLIN_SYNTHETIC = 3;

    private final ConfigurableFileCollection sources;
    private final ConfigurableFileCollection classpath;
    private final SetProperty<String> excludePackages;
    private final SetProperty<String> excludeClasses;
    private final Map<String, Set<String>> excludeMethods;
    private final File outputDir;
    private boolean verbose;

    @Inject
    public ScanApi(ObjectFactory objectFactory) {
        sources = getProject().files();
        classpath = getProject().files();
        excludePackages = objectFactory.setProperty(String.class);
        excludeClasses = objectFactory.setProperty(String.class);
        excludeMethods = new LinkedHashMap<>();
        outputDir = new File(getProject().getBuildDir(), "api");
    }

    @SkipWhenEmpty
    @InputFiles
    public FileCollection getSources() {
        return sources;
    }

    void setSources(FileCollection sources) {
        this.sources.setFrom(sources);
    }

    @CompileClasspath
    @InputFiles
    public FileCollection getClasspath() {
        return classpath;
    }

    void setClasspath(FileCollection classpath) {
        this.classpath.setFrom(classpath);
    }

    @Input
    public SetProperty<String> getExcludePackages() {
        return excludePackages;
    }

    void setExcludePackages(SetProperty<String> excludePackages) {
        this.excludePackages.set(excludePackages);
    }

    @Input
    public SetProperty<String> getExcludeClasses() {
        return excludeClasses;
    }

    void setExcludeClasses(SetProperty<String> excludeClasses) {
        this.excludeClasses.set(excludeClasses);
    }

    @Input
    public Map<String, Collection<String>> getExcludeMethods() {
        return unmodifiableMap(excludeMethods);
    }

    void setExcludeMethods(Map<String, ? extends Collection<String>> excludeMethods) {
        this.excludeMethods.clear();
        excludeMethods.forEach((key, value) -> this.excludeMethods.put(key, new LinkedHashSet<>(value)));
    }

    @OutputFiles
    public FileCollection getTargets() {
        return getProject().files(
            StreamSupport.stream(sources.spliterator(), false)
                .map(this::toTarget)
                .collect(toList())
        );
    }

    @Console
    public boolean isVerbose() {
        return verbose;
    }

    void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    private File toTarget(File source) {
        return new File(outputDir, source.getName().replaceAll("\\.jar$", ".txt"));
    }

    @TaskAction
    public void scan() {
        try (Scanner scanner = new Scanner(classpath)) {
            for (File source : sources) {
                scanner.scan(source);
            }
        } catch (IOException e) {
            getLogger().error("Failed to write API file", e);
            throw new InvalidUserCodeException(e.getMessage(), e);
        }
    }

    class Scanner implements Closeable {
        private final URLClassLoader classpathLoader;
        private final Class<? extends Annotation> metadataClass;
        private final Method classTypeMethod;
        private Collection<String> internalAnnotations;
        private Collection<String> invisibleAnnotations;
        private Collection<String> inheritedAnnotations;

        @SuppressWarnings("unchecked")
        Scanner(URLClassLoader classpathLoader) {
            this.classpathLoader = classpathLoader;
            this.invisibleAnnotations = ANNOTATION_BLACKLIST;
            this.inheritedAnnotations = emptySet();
            this.internalAnnotations = emptySet();

            Class<? extends Annotation> kClass;
            Method kMethod;
            try {
                kClass = (Class<Annotation>) Class.forName(KOTLIN_METADATA, true, classpathLoader);
                kMethod = kClass.getDeclaredMethod(KOTLIN_CLASSTYPE_METHOD);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                kClass = null;
                kMethod = null;
            }

            metadataClass = kClass;
            classTypeMethod = kMethod;
        }

        Scanner(FileCollection classpath) throws MalformedURLException {
            this(new URLClassLoader(toURLs(classpath)));
        }

        @Override
        public void close() throws IOException {
            classpathLoader.close();
        }

        void scan(File source) {
            File target = toTarget(source);
            getLogger().info("API file: {}", target.getAbsolutePath());
            try (
                URLClassLoader appLoader = new URLClassLoader(new URL[]{toURL(source)}, classpathLoader);
                ApiPrintWriter writer = new ApiPrintWriter(target, "UTF-8")
            ) {
                scan(writer, appLoader);
            } catch (IOException e) {
                getLogger().error("API scan has failed", e);
                throw new InvalidUserCodeException(e.getMessage(), e);
            }
        }

        void scan(ApiPrintWriter writer, ClassLoader appLoader) {
            try (ScanResult result = new ClassGraph()
                    .blacklistPackages(excludePackages.get().toArray(new String[0]))
                    .blacklistClasses(excludeClasses.get().toArray(new String[0]))
                    .overrideClassLoaders(appLoader)
                    .ignoreParentClassLoaders()
                    .ignoreMethodVisibility()
                    .ignoreFieldVisibility()
                    .disableDirScanning()
                    .enableStaticFinalFieldConstantInitializerValues()
                    .enableExternalClasses()
                    .enableAnnotationInfo()
                    .enableClassInfo()
                    .enableMethodInfo()
                    .enableFieldInfo()
                    .verbose(verbose)
                    .scan()) {
                loadAnnotationCaches(result);
                getLogger().info("Annotations:");
                getLogger().info("- Inherited: {}", inheritedAnnotations);
                getLogger().info("- Internal:  {}", internalAnnotations);
                getLogger().info("- Invisible: {}", invisibleAnnotations);
                writeApis(writer, result);
            }
        }

        private void loadAnnotationCaches(ScanResult result) {
            ClassInfoList scannedAnnotations = result.getAllAnnotations();
            Set<String> internal = scannedAnnotations.getNames().stream()
                .filter(s -> s.endsWith(INTERNAL_ANNOTATION_NAME))
                .collect(toCollection(LinkedHashSet::new));
            internal.add(DEFAULT_INTERNAL_ANNOTATION);
            internalAnnotations = unmodifiableSet(internal);

            Set<String> invisible = internalAnnotations.stream()
                .flatMap(a -> scannedAnnotations
                                .filter(i -> i.hasAnnotation(a))
                                .getNames()
                                .stream())
                .collect(toCollection(LinkedHashSet::new));
            invisible.addAll(ANNOTATION_BLACKLIST);
            invisible.addAll(internal);
            invisibleAnnotations = unmodifiableSet(invisible);

            List<String> inherited = scannedAnnotations
                .filter(a -> a.loadClass().isAnnotationPresent(Inherited.class))
                .getNames();
            inheritedAnnotations = unmodifiableSet(new LinkedHashSet<>(inherited));
        }

        private void writeApis(ApiPrintWriter writer, ScanResult result) {
            Map<String, ClassInfo> allInfo = result.getAllClassesAsMap();
            result.getAllClasses().getNames().forEach(className -> {
                if (className.contains(".internal.")) {
                    // These classes belong to internal Corda packages.
                    return;
                }

                ClassInfo classInfo = allInfo.get(className);
                if (classInfo.isExternalClass()) {
                    // Ignore classes that belong to one of our target ClassLoader's parents.
                    return;
                }

                if (classInfo.isAnnotation() && !isVisibleAnnotation(className)) {
                    // Exclude these annotations from the output,
                    // e.g. because they're internal to Kotlin or Corda.
                    return;
                }

                if (hasInternalAnnotation(classInfo.getAnnotations().directOnly().getNames())) {
                    // Excludes classes annotated with any @CordaInternal annotation.
                    return;
                }

                Class<?> javaClass = result.loadClass(className, false);
                if (!isVisible(javaClass.getModifiers())) {
                    // Excludes private and package-protected classes
                    return;
                }

                if (classInfo.getFullyQualifiedDefiningMethodName() != null) {
                    // Ignore Kotlin auto-generated internal classes
                    // which are not part of the api
                    return;
                }

                int kotlinClassType = getKotlinClassType(javaClass);
                if (kotlinClassType == KOTLIN_SYNTHETIC) {
                    // Exclude classes synthesised by the Kotlin compiler.
                    return;
                }

                writeClass(writer, classInfo);
                writeMethods(writer, classInfo.getDeclaredMethodAndConstructorInfo());
                writeFields(writer, classInfo.getDeclaredFieldInfo());
                writer.println("##");
            });
        }

        private void writeClass(ApiPrintWriter writer, ClassInfo classInfo) {
            if (classInfo.isAnnotation()) {
                writer.println(classInfo, INTERFACE_MASK, emptyList());
            } else if (classInfo.isStandardClass()) {
                writer.println(classInfo, CLASS_MASK, toNames(readClassAnnotationsFor(classInfo)).visible);
            } else {
                writer.println(classInfo, INTERFACE_MASK, toNames(readInterfaceAnnotationsFor(classInfo)).visible);
            }
        }

        private void writeMethods(ApiPrintWriter writer, List<MethodInfo> methods) {
            sort(methods);
            for (MethodInfo method : methods) {
                AnnotationInfoList methodAnnotations = method.getAnnotationInfo().directOnly();
                if (isVisible(method.getModifiers()) // Only public and protected methods
                        && !isExcluded(method) // Filter out methods explicitly excluded
                        && isValid(method.getModifiers(), METHOD_MASK) // Excludes bridge methods
                        // Excludes methods annotated as @CordaInternal
                        && !hasInternalAnnotation(methodAnnotations.getNames())
                        && !isEnumConstructor(method)
                        && !isKotlinInternalScope(method)) {
                    writer.println(method, methodAnnotations.filter(this::isVisibleAnnotation), "  ");
                }
            }
        }

        private void writeFields(ApiPrintWriter writer, List<FieldInfo> fields) {
            sort(fields);
            for (FieldInfo field : fields) {
                AnnotationInfoList fieldAnnotations = field.getAnnotationInfo().directOnly();
                if (isVisible(field.getModifiers())
                        && isValid(field.getModifiers(), FIELD_MASK)
                        && !hasInternalAnnotation(fieldAnnotations.getNames())) {
                    writer.println(field, fieldAnnotations.filter(this::isVisibleAnnotation), "  ");
                }
            }
        }

        private int getKotlinClassType(Class<?> javaClass) {
            if (metadataClass != null) {
                Annotation metadata = javaClass.getAnnotation(metadataClass);
                if (metadata != null) {
                    try {
                        return (int) classTypeMethod.invoke(metadata);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        Throwable ex = (e instanceof InvocationTargetException) ? e.getCause() : e;
                        getLogger().error("Failed to read Kotlin annotation", ex);
                        throw new InvalidUserCodeException(ex.getMessage(), ex);
                    }
                }
            }
            return 0;
        }

        private Names toNames(Collection<ClassInfo> classes) {
            Map<Boolean, List<String>> partitioned = classes.stream()
                .map(ClassInfo::getName)
                .filter(ScanApi::isApplicationClass)
                .collect(partitioningBy(this::isVisibleAnnotation, toCollection(ArrayList::new)));
            List<String> visible = partitioned.get(true);
            int idx = visible.indexOf(DONOTIMPLEMENT_ANNOTATION_NAME);
            if (idx != -1) {
                swap(visible, 0, idx);
                sort(visible.subList(1, visible.size()));
            } else {
                sort(visible);
            }
            return new Names(visible, ordering(partitioned.get(false)));
        }

        private Set<ClassInfo> readClassAnnotationsFor(ClassInfo classInfo) {
            // The annotation ordering doesn't matter, as they will be sorted later.
            Set<ClassInfo> annotations = new HashSet<>(classInfo.getAnnotations().directOnly());
            annotations.addAll(selectInheritedAnnotations(classInfo.getSuperclasses()));
            annotations.addAll(selectInheritedAnnotations(classInfo.getInterfaces().getImplementedInterfaces()));
            return annotations;
        }

        private Set<ClassInfo> readInterfaceAnnotationsFor(ClassInfo classInfo) {
            // The annotation ordering doesn't matter, as they will be sorted later.
            Set<ClassInfo> annotations = new HashSet<>(classInfo.getAnnotations().directOnly());
            annotations.addAll(selectInheritedAnnotations(classInfo.getInterfaces()));
            return annotations;
        }

        /**
         * Returns those annotations which have themselves been annotated as "Inherited".
         */
        private List<ClassInfo> selectInheritedAnnotations(Collection<ClassInfo> classes) {
            return classes.stream()
                .flatMap(cls -> cls.getAnnotations().directOnly().stream())
                .filter(ann -> inheritedAnnotations.contains(ann.getName()))
                .collect(toList());
        }

        private boolean isVisibleAnnotation(AnnotationInfo annotation) {
            return isVisibleAnnotation(annotation.getName());
        }

        private boolean isVisibleAnnotation(String className) {
            return !invisibleAnnotations.contains(className);
        }

        private boolean hasInternalAnnotation(Collection<String> annotationNames) {
            return annotationNames.stream().anyMatch(internalAnnotations::contains);
        }
    }

    private static <T extends Comparable<? super T>> List<T> ordering(List<T> list) {
        sort(list);
        return list;
    }

    private static boolean isKotlinInternalScope(MethodInfo method) {
        return method.getName().indexOf('$') >= 0;
    }

    // Kotlin 1.2 declares Enum constructors as protected, although
    // both Java and Kotlin 1.3 declare them as private. But exclude
    // them because Enum classes are final anyway.
    private static boolean isEnumConstructor(MethodInfo method) {
        return method.isConstructor() && method.getClassInfo().extendsSuperclass(ENUM_BASE_CLASS);
    }

    private static boolean isValid(int modifiers, int mask) {
        return (modifiers & mask) == modifiers;
    }

    private boolean isExcluded(MethodInfo method) {
        final String methodSignature = method.getName() + method.getTypeDescriptorStr();
        final String className = method.getClassInfo().getName();

        return this.excludeMethods.containsKey(className) &&
                this.excludeMethods.get(className).contains(methodSignature);
    }

    private static boolean isVisible(int accessFlags) {
        return (accessFlags & VISIBILITY_MASK) != 0;
    }

    private static boolean isApplicationClass(String typeName) {
        return !typeName.startsWith("java.") && !typeName.startsWith("kotlin.");
    }

    private static URL toURL(File file) throws MalformedURLException {
        return file.toURI().toURL();
    }

    private static URL[] toURLs(Iterable<File> files) throws MalformedURLException {
        List<URL> urls = new LinkedList<>();
        for (File file : files) {
            urls.add(toURL(file));
        }
        return urls.toArray(new URL[0]);
    }
}

class Names {
    List<String> visible;
    @SuppressWarnings("WeakerAccess")
    List<String> hidden;

    Names(List<String> visible, List<String> hidden) {
        this.visible = unmodifiable(visible);
        this.hidden = unmodifiable(hidden);
    }

    private static <T> List<T> unmodifiable(List<T> list) {
        return list.isEmpty() ? emptyList() : unmodifiableList(new ArrayList<>(list));
    }
}
