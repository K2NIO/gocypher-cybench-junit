package com.gocypher.cybench.t2b.transform;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import org.apache.commons.math3.util.Pair;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.generators.core.ClassInfo;
import org.openjdk.jmh.generators.core.FieldInfo;
import org.openjdk.jmh.generators.core.MetadataInfo;
import org.openjdk.jmh.generators.core.ParameterInfo;
import org.openjdk.jmh.generators.reflection.T2BClassInfo;

import com.gocypher.cybench.T2BMapper;
import com.gocypher.cybench.T2BUtils;
import com.gocypher.cybench.Test2Benchmark;
import com.gocypher.cybench.core.annotation.BenchmarkMetaData;
import com.gocypher.cybench.core.annotation.BenchmarkTag;
import com.gocypher.cybench.core.annotation.CyBenchMetadataList;
import com.gocypher.cybench.t2b.transform.annotation.*;
import com.gocypher.cybench.t2b.transform.metadata.BenchmarkMetadata;

import javassist.*;
import javassist.bytecode.*;

public class T2BClassTransformer {

    private static final String SYS_PROP_CLASS_NAME_SUFFIX = "t2b.bench.class.name.suffix";
    private static final String DEFAULT_CLASS_NAME_SUFFIX = "BenchmarkByT2B";
    private static String benchClassNameSuffix = System.getProperty(SYS_PROP_CLASS_NAME_SUFFIX,
            DEFAULT_CLASS_NAME_SUFFIX);

    private ClassInfo clsInfo;
    private CtClass alteredClass;
    List<org.openjdk.jmh.generators.core.MethodInfo> benchmarksList = new ArrayList<>();
    private ClassInfo aClsInfo;

    public T2BClassTransformer(ClassInfo clsInfo) {
        this.clsInfo = checkClassLoaderForAlteredClass(clsInfo);
    }

    private ClassInfo checkClassLoaderForAlteredClass(ClassInfo clsInfo) {
        String clsName = getClassName(clsInfo);
        String alteredClassName = getAlteredClassName(clsName);
        try {
            Class<?> alteredCLClass = Class.forName(alteredClassName);
            return new T2BClassInfo(alteredCLClass);
        } catch (Exception exc) {
            try {
                getCtClass(clsName);
            } catch (Exception exc2) {
                exc2.printStackTrace(); // TODO:
            }
            return clsInfo;
        }
    }

    public boolean hasNonStaticFields() {
        for (FieldInfo fieldInfo : getAllFields(clsInfo)) {
            if (!fieldInfo.isStatic()) {
                return true;
            }
        }

        return false;
    }

    public static Collection<FieldInfo> getAllFields(ClassInfo ci) {
        Collection<FieldInfo> ls = new ArrayList<>();
        do {
            ls.addAll(ci.getFields());
        } while ((ci = ci.getSuperClass()) != null);
        return ls;
    }

    public void doTransform(T2BMapper... t2bMappers) {
        if (hasNonStaticFields()) {
            annotateClassState();
        }

        annotateClassMetadataList(clsInfo);

        for (org.openjdk.jmh.generators.core.MethodInfo methodInfo : clsInfo.getMethods()) {
            annotateMethod(methodInfo, t2bMappers);
        }
    }

    public void storeTransformedClass(String dir) {
        if (isClassAltered()) {
            try {
                storeClass(dir);
                toClass();
            } catch (Exception exc) {
                Test2Benchmark.errWithTrace("failed to use altered class: " + getAlteredClassName(), exc);
            }
        }

    }

    public void annotateClassState() {
        Annotation stateAnnotation = clsInfo.getAnnotation(State.class);
        if (stateAnnotation == null) {
            annotateStateClass(clsInfo, State.class.getName(), STATE_ANNOTATION_MEMBERS);
        }
    }

    public void annotateBenchmark(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        annotateBenchmarkMethod(methodInfo, Benchmark.class.getName(), null);
    }

    public void annotateBenchmarkTag(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        String methodSignature = getSignature(methodInfo);
        Map<String, String> tagMembers = new LinkedHashMap<>(1);
        tagMembers.put("tag", UUID.nameUUIDFromBytes(methodSignature.getBytes()).toString());

        annotateBenchmarkMethod(methodInfo, BenchmarkTag.class.getName(), tagMembers);
    }

    // NOTE: Javassist does not support @Repeatable annotations yet...
    public void annotateBenchmarkMetadata(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        Map<String, String> metaDataMap = BenchmarkMetadata.fillMetadata(methodInfo);

        for (Map.Entry<String, String> mde : metaDataMap.entrySet()) {
            Map<String, String> tagMembers = new LinkedHashMap<>(2);
            tagMembers.put("key", mde.getKey());
            tagMembers.put("value", mde.getValue());
            annotateBenchmarkMethod(methodInfo, BenchmarkMetaData.class.getName(), tagMembers);
        }
    }

    public void annotateBenchmarkMetadataList(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        List<Map<String, String>> metaDataList = getMetadata(methodInfo);
        if (metaDataList.isEmpty()) {
            return;
        }

        annotateBenchmarkMethod(methodInfo, CyBenchMetadataList.class.getName(), BenchmarkMetaData.class.getName(),
                metaDataList);
    }

    public List<Map<String, String>> getMetadata(MetadataInfo metadataInfo) {
        Map<String, String> metaDataMap = BenchmarkMetadata.fillMetadata(metadataInfo);
        List<Map<String, String>> metaDataList = new ArrayList<>(metaDataMap.size());

        for (Map.Entry<String, String> mde : metaDataMap.entrySet()) {
            Map<String, String> tagMembers = new LinkedHashMap<>(2);
            tagMembers.put("key", mde.getKey());
            tagMembers.put("value", mde.getValue());
            metaDataList.add(tagMembers);
        }

        return metaDataList;
    }

    // NOTE: Javassist does not support @Repeatable annotations yet...
    public void annotateClassMetadata(ClassInfo classInfo) {
        Map<String, String> metaDataMap = BenchmarkMetadata.fillMetadata(classInfo);

        for (Map.Entry<String, String> mde : metaDataMap.entrySet()) {
            Map<String, String> tagMembers = new LinkedHashMap<>(2);
            tagMembers.put("key", mde.getKey());
            tagMembers.put("value", mde.getValue());
            annotateBenchmarkClass(classInfo, BenchmarkMetaData.class.getName(), tagMembers);
        }
    }

    public void annotateClassMetadataList(ClassInfo classInfo) {
        List<Map<String, String>> metaDataList = getMetadata(classInfo);
        if (metaDataList.isEmpty()) {
            return;
        }

        annotateBenchmarkClass(classInfo, CyBenchMetadataList.class.getName(), BenchmarkMetaData.class.getName(),
                metaDataList);
    }

    private static Map<String, Pair<String, String>> LEVEL_ANNOTATION_MEMBERS = new LinkedHashMap<>();
    static {
        LEVEL_ANNOTATION_MEMBERS.put("value", new Pair<>(Level.class.getName(), Level.Trial.name()));
    }

    public void annotateMethodSetup(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        annotateStateMethod(methodInfo, Setup.class.getName(), LEVEL_ANNOTATION_MEMBERS);
    }

    public void annotateMethodTearDown(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        annotateStateMethod(methodInfo, TearDown.class.getName(), LEVEL_ANNOTATION_MEMBERS);
    }

    public String getClassName() {
        return alteredClass == null ? getClassName(clsInfo) : alteredClass.getName();
    }

    public static String getAlteredClassName(String className) {
        if (className.contains("$")) {
            String[] cnt = className.split("\\$");
            cnt[0] = cnt[0] + benchClassNameSuffix;
            return String.join("$", cnt);
        } else {
            return className + benchClassNameSuffix;
        }
    }

    private static Map<String, Pair<String, String>> STATE_ANNOTATION_MEMBERS = new LinkedHashMap<>();
    static {
        STATE_ANNOTATION_MEMBERS.put("value", new Pair<>(Scope.class.getName(), Scope.Benchmark.name()));
    }

    public void annotateStateClass(ClassInfo classInfo, String annotationName,
            Map<String, Pair<String, String>> membersMap) {
        String clsName = getClassName(classInfo);
        try {
            addClassEnumAnnotation(clsName, annotationName, membersMap);
            Test2Benchmark.log(
                    String.format("%-20.20s: %s", "Added", "@" + annotationName + " annotation for class " + clsName));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace("failed to add @" + annotationName + " annotation for class " + clsName, exc);
        }
    }

    public void annotateBenchmarkClass(ClassInfo classInfo, String annotationName, Map<String, String> membersMap) {
        String clsName = getClassName(classInfo);
        try {
            addClassAnnotation(clsName, annotationName, membersMap);
            Test2Benchmark.log(
                    String.format("%-20.20s: %s", "Added", "@" + annotationName + " annotation for class " + clsName));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace("failed to add @" + annotationName + " annotation for class " + clsName, exc);
        }
    }

    public void annotateBenchmarkClass(ClassInfo classInfo, String arrayAnnotationName, String annotationsName,
            List<Map<String, String>> memberList) {
        String clsName = getClassName(classInfo);
        try {
            addClassAnnotation(clsName, arrayAnnotationName, annotationsName, memberList);
            Test2Benchmark.log(String.format("%-20.20s: %s", "Added",
                    "@" + arrayAnnotationName + " annotation for class " + clsName));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace("failed to add @" + arrayAnnotationName + " annotation for class " + clsName,
                    exc);
        }
    }

    public void annotateBenchmarkMethod(org.openjdk.jmh.generators.core.MethodInfo method, String annotationName,
            Map<String, String> membersMap) {
        try {
            String methodName = method.getName();

            addMethodAnnotation(methodName, annotationName, membersMap);
            Test2Benchmark.log(String.format("%-20.20s: %s", "Added",
                    "@" + annotationName + " annotation for method " + method.getQualifiedName()));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace(
                    "failed to add @" + annotationName + " annotation for method " + method.getQualifiedName(), exc);
        }
    }

    public void annotateBenchmarkMethod(org.openjdk.jmh.generators.core.MethodInfo method, String arrayAnnotationName,
            String annotationsName, List<Map<String, String>> memberList) {
        try {
            String methodName = method.getName();

            addMethodArrayAnnotation(methodName, arrayAnnotationName, annotationsName, memberList);
            Test2Benchmark.log(String.format("%-20.20s: %s", "Added",
                    "@" + arrayAnnotationName + " annotation for method " + method.getQualifiedName()));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace(
                    "failed to add @" + arrayAnnotationName + " annotation for method " + method.getQualifiedName(),
                    exc);
        }
    }

    public void annotateStateMethod(org.openjdk.jmh.generators.core.MethodInfo method, String annotationName,
            Map<String, Pair<String, String>> membersMap) {
        try {
            String methodName = method.getName();

            addMethodEnumAnnotation(methodName, annotationName, membersMap);
            Test2Benchmark.log(String.format("%-20.20s: %s", "Added",
                    "@" + annotationName + " annotation for method " + method.getQualifiedName()));
        } catch (Exception exc) {
            Test2Benchmark.errWithTrace(
                    "failed to add @" + annotationName + " annotation for method " + method.getQualifiedName(), exc);
        }

        annotateClassState();
    }

    public static String getSignature(Method m) {
        String sig;
        try {
            sig = (String) T2BUtils.getFieldValue(m, "signature");
            if (sig != null) {
                return sig;
            }
        } catch (Exception exc) {
            // Test2Benchmark.errWithTrace("failed to get method signature field", exc);
        }

        StringBuilder sb = new StringBuilder(m.getDeclaringClass().getName() + "." + m.getName() + "(");
        for (Class<?> c : m.getParameterTypes()) {
            sb.append((sig = Array.newInstance(c, 0).toString()), 1, sig.indexOf('@'));
        }
        return sb.append(')')
                .append(m.getReturnType() == void.class ? "V"
                        : (sig = Array.newInstance(m.getReturnType(), 0).toString()).substring(1, sig.indexOf('@')))
                .toString();
    }

    public static String getSignature(org.openjdk.jmh.generators.core.MethodInfo methodInfo) {
        try {
            Method m = (Method) T2BUtils.getFieldValue(methodInfo, "m");
            return getSignature(m);
        } catch (Exception exc) {
            StringBuilder sb = new StringBuilder(
                    methodInfo.getDeclaringClass().getName() + "." + methodInfo.getName() + "(");
            for (ParameterInfo pi : methodInfo.getParameters()) {
                sb.append(pi.getType().getName());
            }
            return sb.append(')').append(methodInfo.getReturnType().equals("void") ? "V" : methodInfo.getReturnType())
                    .toString();
        }
    }

    public static Class<?> getClass(ClassInfo classInfo) throws Exception {
        Field f = classInfo.getClass().getSuperclass().getDeclaredField("klass");
        f.setAccessible(true);
        Class<?> cls = (Class<?>) f.get(classInfo);
        return cls;
    }

    public static String getClassName(ClassInfo classInfo) {
        try {
            Class<?> cls = getClass(classInfo);
            return cls.getName();
        } catch (Throwable exc) {
            return classInfo.getQualifiedName();
        }
    }

    private CtClass getCtClass(String className) throws Exception {
        if (alteredClass == null) {
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.getAndRename(className, getAlteredClassName(className));
            Test2Benchmark.log(String.format("%-15.15s: %s", "Rename",
                    "altering class " + className + " and named it " + ctClass.getName()));
            alteredClass = ctClass;

            return ctClass;
        }

        return alteredClass;
    }

    private static void alterClass(CtClass ctClass) throws Exception {
        if (ctClass.isFrozen()) {
            ctClass.defrost();
        }

        makeClassPublic(ctClass);
        makeConstructorPublic(ctClass);
    }

    private static void makeClassPublic(CtClass ctClass) {
        if (!isNestedClass(ctClass) && !Modifier.isPublic(ctClass.getModifiers())) {
            ctClass.setModifiers(ctClass.getModifiers() | Modifier.PUBLIC);
            Test2Benchmark.log(
                    String.format("%-20.20s: %s", "Changed", "visibility to PUBLIC for class " + ctClass.getName()));
        }
    }

    private static void makeConstructorPublic(CtClass ctClass) throws Exception {
        for (CtConstructor constructor : ctClass.getConstructors()) {
            if (constructor.getParameterTypes().length < 2) {
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    constructor.setModifiers(constructor.getModifiers() | Modifier.PUBLIC);
                    Test2Benchmark.log(String.format("%-20.20s: %s", "Changed",
                            "visibility to PUBLIC for constructor " + constructor.getLongName()));
                }
            }
        }
    }

    public CtClass addClassEnumAnnotation(String className, String annotationName,
            Map<String, Pair<String, String>> membersMap) throws Exception {
        return addClassAnnotation(className, new EnumAnnotationBuilder(annotationName, membersMap));
    }

    public CtClass addClassAnnotation(String className, String annotationName, Map<String, String> membersMap)
            throws Exception {
        return addClassAnnotation(className, new StringAnnotationBuilder(annotationName, membersMap));
    }

    public CtClass addClassAnnotation(String className, String arrayAnnotationName, String annotationsName,
            List<Map<String, String>> memberList) throws Exception {
        return addClassAnnotation(className, new ArrayAnnotationBuilder(arrayAnnotationName,
                new DefaultAnnotationArrayBuilder<>(new StringAnnotationBuilder(annotationsName), memberList)));
    }

    public CtClass addClassAnnotation(String className, AnnotationBuilder<?> annotationBuilder) throws Exception {
        CtClass ctClass = getCtClass(className);
        alterClass(ctClass);

        ClassFile classFile = ctClass.getClassFile();
        ConstPool constPool = classFile.getConstPool();

        List<AttributeInfo> classFileAttributes = classFile.getAttributes();
        AnnotationsAttribute annotationsAttribute = getAnnotationAttribute(classFileAttributes);

        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
            classFile.addAttribute(annotationsAttribute);
        }

        annotationsAttribute.addAnnotation(annotationBuilder.buildAnnotation(constPool));

        return ctClass;
    }

    public CtClass addMethodAnnotation(String methodName, String annotationName, Map<String, String> membersMap)
            throws Exception {
        return addMethodAnnotation(methodName, new StringAnnotationBuilder(annotationName, membersMap));
    }

    public CtClass addMethodEnumAnnotation(String methodName, String annotationName,
            Map<String, Pair<String, String>> membersMap) throws Exception {
        return addMethodAnnotation(methodName, new EnumAnnotationBuilder(annotationName, membersMap));
    }

    public CtClass addMethodArrayAnnotation(String methodName, String arrayAnnotationName, String annotationsName,
            List<Map<String, String>> memberList) throws Exception {
        return addMethodAnnotation(methodName, new ArrayAnnotationBuilder(arrayAnnotationName,
                new DefaultAnnotationArrayBuilder<>(new StringAnnotationBuilder(annotationsName), memberList)));
    }

    private static void makeMethodPublic(CtMethod method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            method.setModifiers(method.getModifiers() | Modifier.PUBLIC);
            Test2Benchmark.log(String.format("%-20.20s: %s", "Changed",
                    "visibility to PUBLIC for method " + method.getLongName()));
        }
    }

    public CtClass addMethodAnnotation(String methodName, AnnotationBuilder<?> annotationBuilder) throws Exception {
        CtClass ctClass = getCtClass(getClassName());
        alterClass(ctClass);

        CtMethod method = ctClass.getDeclaredMethod(methodName);
        makeMethodPublic(method);

        MethodInfo methodInfo = method.getMethodInfo();
        ConstPool constPool = methodInfo.getConstPool();

        List<AttributeInfo> methodAttributes = methodInfo.getAttributes();
        AnnotationsAttribute annotationsAttribute = getAnnotationAttribute(methodAttributes);

        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
            methodInfo.addAttribute(annotationsAttribute);
        }

        annotationsAttribute.addAnnotation(annotationBuilder.buildAnnotation(constPool));

        return ctClass;
    }

    static AnnotationsAttribute getAnnotationAttribute(List<AttributeInfo> attributes) {
        for (Object object : attributes) {
            if (AnnotationsAttribute.class.isAssignableFrom(object.getClass())) {
                return (AnnotationsAttribute) object;
            }
        }

        return null;
    }

    private static boolean isNestedClass(CtClass cls) {
        return cls.getName().contains("$");
    }

    public void toClass() throws Exception {
        if (alteredClass != null) {
            Class<?> cls = alteredClass.toClass();
            aClsInfo = new T2BClassInfo(cls);
        }
    }

    public ClassInfo getClassInfo() {
        return aClsInfo == null ? clsInfo : aClsInfo;
    }

    public boolean isClassAltered() {
        return alteredClass != null;
    }

    public void storeClass(String classDir) throws Exception {
        alteredClass.writeFile(new File(classDir).getCanonicalPath());
    }

    public String getAlteredClassName() {
        return alteredClass == null ? "null" : alteredClass.getName();
    }

    public boolean hasBenchmarks() {
        return !benchmarksList.isEmpty();
    }

    public Collection<org.openjdk.jmh.generators.core.MethodInfo> getBenchmarkMethods() {
        if (isClassAltered()) {
            Collection<org.openjdk.jmh.generators.core.MethodInfo> amil = aClsInfo.getMethods();
            for (int i = 0; i < benchmarksList.size(); i++) {
                org.openjdk.jmh.generators.core.MethodInfo mi = benchmarksList.get(i);
                org.openjdk.jmh.generators.core.MethodInfo ami = getAlteredMethod(mi, amil);
                if (ami != null) {
                    benchmarksList.set(i, ami);
                }
            }
        }

        return benchmarksList;
    }

    private static org.openjdk.jmh.generators.core.MethodInfo getAlteredMethod(
            org.openjdk.jmh.generators.core.MethodInfo mi,
            Collection<org.openjdk.jmh.generators.core.MethodInfo> amil) {
        for (org.openjdk.jmh.generators.core.MethodInfo ami : amil) {
            if (ami.getName().equals(mi.getName())) {
                return ami;
            }
        }

        return null;
    }

    public void annotateMethod(org.openjdk.jmh.generators.core.MethodInfo mi, T2BMapper... t2BMappers) {
        T2BMapper.MethodState testValid = isValidTest(mi, t2BMappers);
        if (testValid == T2BMapper.MethodState.VALID) {
            annotateBenchmark(mi);
            annotateBenchmarkTag(mi);
            annotateBenchmarkMetadataList(mi);
            benchmarksList.add(mi);
        } else if (isSetupMethod(mi, t2BMappers)) {
            annotateMethodSetup(mi);
        } else if (isTearDownMethod(mi, t2BMappers)) {
            annotateMethodTearDown(mi);
        } else if (testValid != T2BMapper.MethodState.NOT_TEST) {
            Test2Benchmark.log(String.format("%-20.20s: %s", "Skipping",
                    "test method " + mi.getQualifiedName() + ", reason: " + testValid.name()));
        }
    }

    private static T2BMapper.MethodState isValidTest(org.openjdk.jmh.generators.core.MethodInfo mi,
            T2BMapper... t2bMappers) {
        if (t2bMappers != null) {
            for (T2BMapper mapper : t2bMappers) {
                T2BMapper.MethodState ms = mapper.isValid(mi);
                if (ms == T2BMapper.MethodState.NOT_TEST) {
                    continue;
                } else {
                    return ms;
                }
            }
        }

        return T2BMapper.MethodState.NOT_TEST;
    }

    private static boolean isSetupMethod(org.openjdk.jmh.generators.core.MethodInfo mi, T2BMapper... t2bMappers) {
        if (t2bMappers != null) {
            for (T2BMapper mapper : t2bMappers) {
                boolean setupMethod = mapper.isSetupMethod(mi);
                if (setupMethod) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isTearDownMethod(org.openjdk.jmh.generators.core.MethodInfo mi, T2BMapper... t2bMappers) {
        if (t2bMappers != null) {
            for (T2BMapper mapper : t2bMappers) {
                boolean setupMethod = mapper.isTearDownMethod(mi);
                if (setupMethod) {
                    return true;
                }
            }
        }

        return false;
    }

}
