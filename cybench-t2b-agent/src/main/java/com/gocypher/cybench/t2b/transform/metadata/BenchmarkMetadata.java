/*
 * Copyright (C) 2020-2022, K2N.IO.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 */

package com.gocypher.cybench.t2b.transform.metadata;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.openjdk.jmh.generators.core.ClassInfo;
import org.openjdk.jmh.generators.core.MetadataInfo;
import org.openjdk.jmh.generators.core.MethodInfo;
import org.slf4j.Logger;

import com.gocypher.cybench.core.utils.SecurityUtils;
import com.gocypher.cybench.t2b.transform.AbstractClassTransformer;
import com.gocypher.cybench.t2b.utils.LogUtils;

public class BenchmarkMetadata {
    private static Logger LOGGER = LogUtils.getLogger(BenchmarkMetadata.class);

    private static final String SYS_PROP_METADATA_CONFIG = "t2b.metadata.cfg.path";
    private static final String DEFAULT_METADATA_CONFIG_PATH = "config/metadata.properties";
    private static String configPath = System.getProperty(SYS_PROP_METADATA_CONFIG, DEFAULT_METADATA_CONFIG_PATH);

    private static final Pattern VARIABLE_EXP_PATTERN = Pattern.compile("(\\$\\{(.[^:]*)\\})+[:]*(\\{(.[^:]*)\\})*");
    private static final Pattern VARIABLE_EXP_RANGE_PATTERN = Pattern.compile("(\\$\\{.+\\})");

    private static final Pattern VARIABLE_METHOD_PATTERN = Pattern.compile("method\\.(.[^:]+)");
    private static final Pattern VARIABLE_CLASS_PATTERN = Pattern.compile("class\\.(.[^:]+)");
    private static final Pattern VARIABLE_PACKAGE_PATTERN = Pattern.compile("package\\.(.[^:]+)");
    private static final Pattern VARIABLE_SYS_PROP_PATTERN = Pattern.compile("sys#(.[^:]+)");
    private static final Pattern VARIABLE_ENV_VAR_PATTERN = Pattern.compile("env#(.[^:]+)");
    private static final Pattern VARIABLE_VM_VAR_PATTERN = Pattern.compile("vm#(.[^:]+)");

    private static final Pattern VARIABLE_EXP_METHOD_PATTERN = Pattern.compile("\\$\\{method\\.(.[^:]+)\\}");

    private static final String CLASS_METADATA_MAP_KEY = "class";
    private static final String METHOD_METADATA_MAP_KEY = "method";
    private static final String CLASS_METADATA_PROPS_PREFIX = "class.";
    private static final String METHOD_METADATA_PROPS_PREFIX = "method.";

    private static final String SESSION_ENTRY_KEY = "session";
    private static final String SESSION_VALUE_EXP = "${sys#t2b.session.id}";

    private static final String WRAPPED_API_METHOD_NAME = "wrappedApiMethodName";
    private static final String WRAPPED_API_METHOD_HASH = "wrappedApiMethodHash";

    private static final String FALLBACK_VALUE = "-";

    private static Map<String, Map<String, String>> metadataConfig = new HashMap<>(2);

    static {
        loadConfig(configPath);
    }

    private BenchmarkMetadata() {
    }

    protected static void loadConfig(String cfgPath) {
        Properties metaDataCfgProps = new Properties();
        if (new File(cfgPath).exists()) {
            try (Reader rdr = new BufferedReader(new FileReader(cfgPath))) {
                metaDataCfgProps.load(rdr);
            } catch (IOException exc) {
                LOGGER.error("Failed to load metadata config from: {}, reason: {}", cfgPath, exc.getLocalizedMessage());
            }
        } else {
            String cfgProp = System.getProperty(SYS_PROP_METADATA_CONFIG);
            if (cfgProp != null) {
                LOGGER.warn("System property {} defined metadata configuration file {} not found!",
                        SYS_PROP_METADATA_CONFIG, cfgPath);
            } else {
                LOGGER.info("Default metadata configuration file {} not found!", cfgPath);
            }
        }

        metadataConfig.put(CLASS_METADATA_MAP_KEY, new HashMap<>());
        metadataConfig.put(METHOD_METADATA_MAP_KEY, new HashMap<>());

        for (Map.Entry<?, ?> mdProp : metaDataCfgProps.entrySet()) {
            String mdpKey = (String) mdProp.getKey();
            String mdpValue = (String) mdProp.getValue();

            if (mdpKey.startsWith(CLASS_METADATA_PROPS_PREFIX)) {
                if (isClassMetadataPropValid(mdpValue)) {
                    metadataConfig.get(CLASS_METADATA_MAP_KEY)
                            .put(mdpKey.substring(CLASS_METADATA_PROPS_PREFIX.length()), mdpValue);
                } else {
                    LOGGER.warn("Found invalid metadata configuration property for {} scope: {}={}",
                            CLASS_METADATA_MAP_KEY, mdpKey, mdpValue);
                }
            } else if (mdpKey.startsWith(METHOD_METADATA_PROPS_PREFIX)) {
                metadataConfig.get(METHOD_METADATA_MAP_KEY).put(mdpKey.substring(METHOD_METADATA_PROPS_PREFIX.length()),
                        mdpValue);
            } else {
                metadataConfig.get(METHOD_METADATA_MAP_KEY).put(mdpKey, mdpValue);
            }
        }

        verify(metadataConfig);
    }

    protected static void verify(Map<String, Map<String, String>> metadataCfg) {
        // Map<String, String> cMetadataMap = metadataCfg.get(CLASS_METADATA_MAP_KEY);
        // String mEntry = cMetadataMap.get(SESSION_ENTRY_KEY);
        Map<String, String> mMetadataMap = metadataCfg.get(METHOD_METADATA_MAP_KEY);
        String mEntry = mMetadataMap.get(SESSION_ENTRY_KEY);

        if (StringUtils.isAllEmpty(mEntry)) {
            mMetadataMap.put(SESSION_ENTRY_KEY, SESSION_VALUE_EXP);
        }

        mEntry = mMetadataMap.get(WRAPPED_API_METHOD_NAME);
        if (StringUtils.isEmpty(mEntry)) {
            mMetadataMap.put(WRAPPED_API_METHOD_NAME, "${method.qualified.name}");
        }

        mEntry = mMetadataMap.get(WRAPPED_API_METHOD_HASH);
        if (StringUtils.isEmpty(mEntry)) {
            mMetadataMap.put(WRAPPED_API_METHOD_HASH, "${method.signature.hash}");
        }
    }

    protected static boolean isClassMetadataPropValid(String propValue) {
        return !VARIABLE_EXP_METHOD_PATTERN.matcher(propValue).matches();
    }

    public static boolean isClassMetadataEmpty() {
        return isMetadataEmpty(CLASS_METADATA_MAP_KEY);
    }

    public static boolean isMethodMetadataEmpty() {
        return isMetadataEmpty(METHOD_METADATA_MAP_KEY);
    }

    public static boolean isMetadataEmpty(String scope) {
        Map<?, ?> cMap = getMetadata(scope);

        return cMap == null || cMap.isEmpty();
    }

    public static Map<String, String> getClassMetadata() {
        return getMetadata(CLASS_METADATA_MAP_KEY);
    }

    public static Map<String, String> getMethodMetadata() {
        return getMetadata(METHOD_METADATA_MAP_KEY);
    }

    public static Map<String, String> getMetadata(String scope) {
        if (metadataConfig == null) {
            return null;
        }

        return metadataConfig.get(scope);
    }

    public static Map<String, String> fillMetadata(MetadataInfo metadataInfo) {
        Map<String, String> metadataCfg = metadataInfo instanceof ClassInfo ? getClassMetadata() : getMethodMetadata();
        Map<String, String> metaDataMap = new HashMap<>(metadataCfg.size());

        for (Map.Entry<String, String> cfgEntry : metadataCfg.entrySet()) {
            metaDataMap.put(cfgEntry.getKey(), fillMetadataValue(cfgEntry.getValue(), metadataInfo));
        }

        return metaDataMap;
    }

    public static Map<String, String> fillMetadata(Method method) {
        Map<String, String> metadataCfg = getMethodMetadata();
        Map<String, String> metaDataMap = new HashMap<>(metadataCfg.size());

        for (Map.Entry<String, String> cfgEntry : metadataCfg.entrySet()) {
            metaDataMap.put(cfgEntry.getKey(), fillMetadataValue(cfgEntry.getValue(), method));
        }

        return metaDataMap;
    }

    protected static String fillMetadataValue(String value, MetadataInfo metadataInfo) {
        if (isVariableValue(value)) {
            return VARIABLE_EXP_RANGE_PATTERN.matcher(value).replaceAll(resolveVarExpressionValue(value, metadataInfo));
        }

        return value;
    }

    protected static String fillMetadataValue(String value, Method method) {
        if (isVariableValue(value)) {
            return VARIABLE_EXP_RANGE_PATTERN.matcher(value).replaceAll(resolveVarExpressionValue(value, method));
        }

        return value;
    }

    protected static boolean isVariableValue(String value) {
        return VARIABLE_EXP_PATTERN.matcher(value).find();
    }

    protected static String resolveVarExpressionValue(String value, MetadataInfo metadataInfo) {
        Matcher vMatcher = VARIABLE_EXP_PATTERN.matcher(value);

        while (vMatcher.find()) {
            String varName = vMatcher.group(2);
            String defaultValue = vMatcher.group(4);

            String varValue = resolveVarValue(varName, metadataInfo);
            if (varValue == null && defaultValue != null) {
                varValue = defaultValue;
            }

            if (varValue != null) {
                return varValue;
            }
        }

        return FALLBACK_VALUE;
    }

    protected static String resolveVarExpressionValue(String value, Method method) {
        Matcher vMatcher = VARIABLE_EXP_PATTERN.matcher(value);

        while (vMatcher.find()) {
            String varName = vMatcher.group(2);
            String defaultValue = vMatcher.group(4);

            String varValue = resolveVarValue(varName, method);
            if (varValue == null && defaultValue != null) {
                varValue = defaultValue;
            }

            if (varValue != null) {
                return varValue;
            }
        }

        return FALLBACK_VALUE;
    }

    protected static String resolveVarValue(String variable, MetadataInfo metadataInfo) {
        try {
            String varValue = null;
            if (VARIABLE_SYS_PROP_PATTERN.matcher(variable).matches()
                    || VARIABLE_ENV_VAR_PATTERN.matcher(variable).matches()
                    || VARIABLE_VM_VAR_PATTERN.matcher(variable).matches()) {
                varValue = EnvValueResolver.getValue(variable);
            } else if (VARIABLE_CLASS_PATTERN.matcher(variable).matches()) {
                ClassInfo classInfo;
                if (metadataInfo instanceof ClassInfo) {
                    classInfo = (ClassInfo) metadataInfo;
                } else if (metadataInfo instanceof MetadataInfo) {
                    classInfo = ((MethodInfo) metadataInfo).getDeclaringClass();
                } else {
                    LOGGER.warn("Unknown class metadata entity found: {}", metadataInfo.getClass().getName());
                    classInfo = null;
                }
                if (classInfo != null) {
                    varValue = ClassValueResolver.getValue(variable, classInfo);
                }
            } else if (VARIABLE_METHOD_PATTERN.matcher(variable).matches()) {
                MethodInfo methodInfo;
                if (metadataInfo instanceof MethodInfo) {
                    methodInfo = (MethodInfo) metadataInfo;
                } else {
                    LOGGER.warn("Unknown method metadata entity found: {}", metadataInfo.getClass().getName());
                    methodInfo = null;
                }
                if (methodInfo != null) {
                    varValue = MethodValueResolver.getValue(variable, methodInfo);
                }
            } else if (VARIABLE_PACKAGE_PATTERN.matcher(variable).matches()) {
                Package pckg = null;
                if (metadataInfo instanceof ClassInfo) {
                    pckg = AbstractClassTransformer.getClass((ClassInfo) metadataInfo).getPackage();
                } else if (metadataInfo instanceof MetadataInfo) {
                    pckg = AbstractClassTransformer.getClass(((MethodInfo) metadataInfo).getDeclaringClass())
                            .getPackage();
                } else {
                    LOGGER.warn("Unknown package metadata entity found: {}", metadataInfo.getClass().getName());
                    pckg = null;
                }

                if (pckg != null) {
                    varValue = PackageValueResolver.getValue(variable, pckg);
                }
            } else {
                throw new InvalidVariableException("Unknown variable: " + variable);
            }

            return varValue;
        } catch (InvalidVariableException exc) {
            LOGGER.warn(exc.getLocalizedMessage());
        } catch (Exception exc) {
            LOGGER.error("Failed to resolve variable value for: {}, reason: {}", variable, exc.getLocalizedMessage());
        }
        return null;
    }

    protected static String resolveVarValue(String variable, Method method) {
        try {
            String varValue = null;
            if (VARIABLE_SYS_PROP_PATTERN.matcher(variable).matches()
                    || VARIABLE_ENV_VAR_PATTERN.matcher(variable).matches()
                    || VARIABLE_VM_VAR_PATTERN.matcher(variable).matches()) {
                varValue = EnvValueResolver.getValue(variable);
            } else if (VARIABLE_CLASS_PATTERN.matcher(variable).matches()) {
                varValue = ClassValueResolver.getValue(variable, method.getDeclaringClass());
            } else if (VARIABLE_METHOD_PATTERN.matcher(variable).matches()) {
                varValue = MethodValueResolver.getValue(variable, method);
            } else if (VARIABLE_PACKAGE_PATTERN.matcher(variable).matches()) {
                varValue = PackageValueResolver.getValue(variable, method.getDeclaringClass().getPackage());
            } else {
                throw new InvalidVariableException("Unknown variable: " + variable);
            }

            return varValue;
        } catch (InvalidVariableException exc) {
            LOGGER.warn(exc.getLocalizedMessage());
        } catch (Exception exc) {
            LOGGER.error("Failed to resolve variable value for: {}, reason: {}", variable, exc.getLocalizedMessage());
        }
        return null;
    }

    static class EnvValueResolver {
        static final String SYS_PROP_VAR = "sys#";
        static final String ENV_VAR = "env#";
        static final String VM_VAR = "vm#";

        static final String VM_TIME_MILLIS = "time.millis";
        static final String VM_TIME_NANOS = "time.nanos";
        static final String VM_UUID = "uuid";
        static final String VM_RANDOM = "random";

        static String getValue(String variable) {
            if (variable.startsWith(SYS_PROP_VAR)) {
                String propName = variable.substring(SYS_PROP_VAR.length());

                return System.getProperty(propName);
            } else if (variable.startsWith(ENV_VAR)) {
                String varName = variable.substring(ENV_VAR.length());

                return System.getenv(varName);
            } else if (variable.startsWith(VM_VAR)) {
                String varName = variable.substring(VM_VAR.length());

                return getVMVariable(varName);
            }

            return null;
        }

        static String getVMVariable(String varName) {
            if (varName != null) {
                switch (varName) {
                case VM_TIME_MILLIS:
                    return String.valueOf(System.currentTimeMillis());
                case VM_TIME_NANOS:
                    return String.valueOf(System.nanoTime());
                case VM_UUID:
                    return UUID.randomUUID().toString();
                case VM_RANDOM:
                    return String.valueOf((int) (Math.random() * 10000));
                default:
                }
            }

            return null;
        }
    }

    static class PackageValueResolver {
        static final String VAR_PREFIX = "package.";
        static final String VAR_NAME = VAR_PREFIX + "name";
        static final String VAR_VERSION = VAR_PREFIX + "version";
        static final String VAR_TITLE = VAR_PREFIX + "title";
        static final String VAR_VENDOR = VAR_PREFIX + "vendor";
        static final String VAR_SPEC_VERSION = VAR_PREFIX + "spec.version";
        static final String VAR_SPEC_TITLE = VAR_PREFIX + "spec.title";
        static final String VAR_SPEC_VENDOR = VAR_PREFIX + "spec.vendor";

        static String getValue(String variable, Package pckg) {
            switch (variable) {
            case VAR_NAME:
                return pckg.getName();
            case VAR_VERSION:
                return pckg.getImplementationVersion();
            case VAR_TITLE:
                return pckg.getImplementationTitle();
            case VAR_VENDOR:
                return pckg.getImplementationVendor();
            case VAR_SPEC_VERSION:
                return pckg.getSpecificationVersion();
            case VAR_SPEC_TITLE:
                return pckg.getSpecificationTitle();
            case VAR_SPEC_VENDOR:
                return pckg.getSpecificationVendor();
            default:
                throw new InvalidVariableException("Unknown PACKAGE scope variable: " + variable);
            }
        }
    }

    static class MethodValueResolver {
        static final String VAR_PREFIX = "method.";
        static final String VAR_NAME = VAR_PREFIX + "name";
        static final String VAR_SIGNATURE = VAR_PREFIX + "signature";
        static final String VAR_CLASS = VAR_PREFIX + "class";
        static final String VAR_RETURN_TYPE = VAR_PREFIX + "return.type";
        static final String VAR_QUALIFIED_NAME = VAR_PREFIX + "qualified.name";
        static final String VAR_PARAMETERS = VAR_PREFIX + "parameters";
        static final String VAR_SIGNATURE_HASH = VAR_PREFIX + "signature.hash";

        static String getValue(String variable, MethodInfo methodInfo) {
            switch (variable) {
            case VAR_NAME:
                return methodInfo.getName();
            case VAR_SIGNATURE:
                return AbstractClassTransformer.getSignature(methodInfo);
            case VAR_CLASS:
                return methodInfo.getDeclaringClass().getQualifiedName();
            case VAR_RETURN_TYPE:
                return methodInfo.getReturnType();
            case VAR_QUALIFIED_NAME:
                return methodInfo.getQualifiedName();
            case VAR_PARAMETERS:
                return methodInfo.getParameters().toString();
            case VAR_SIGNATURE_HASH:
                return SecurityUtils.computeStringHash(AbstractClassTransformer.getSignature(methodInfo));
            default:
                throw new InvalidVariableException("Unknown METHOD scope variable: " + variable);
            }
        }

        static String getValue(String variable, Method method) {
            switch (variable) {
            case VAR_NAME:
                return method.getName();
            case VAR_SIGNATURE:
                return AbstractClassTransformer.getSignature(method);
            case VAR_CLASS:
                return method.getDeclaringClass().getName();
            case VAR_RETURN_TYPE:
                return method.getReturnType().getCanonicalName();
            case VAR_QUALIFIED_NAME:
                return method.getDeclaringClass().getName() + "." + method.getName();
            case VAR_PARAMETERS:
                return Arrays.toString(method.getParameters());
            case VAR_SIGNATURE_HASH:
                return SecurityUtils.computeStringHash(AbstractClassTransformer.getSignature(method));
            default:
                throw new InvalidVariableException("Unknown METHOD scope variable: " + variable);
            }
        }
    }

    static class ClassValueResolver {
        static final String VAR_PREFIX = "class.";
        static final String VAR_NAME = VAR_PREFIX + "name";
        static final String VAR_QUALIFIED_NAME = VAR_PREFIX + "qualified.name";
        static final String VAR_PACKAGE = VAR_PREFIX + "package";
        static final String VAR_SUPER_CLASS = VAR_PREFIX + "super";

        static String getValue(String variable, ClassInfo classInfo) {
            switch (variable) {
            case VAR_NAME:
                return classInfo.getName();
            case VAR_QUALIFIED_NAME:
                return classInfo.getQualifiedName();
            case VAR_PACKAGE:
                return classInfo.getPackageName();
            case VAR_SUPER_CLASS:
                return classInfo.getSuperClass().getQualifiedName();
            default:
                throw new InvalidVariableException("Unknown CLASS scope variable: " + variable);
            }
        }

        static String getValue(String variable, Class<?> cls) {
            switch (variable) {
            case VAR_NAME:
                return cls.getSimpleName();
            case VAR_QUALIFIED_NAME:
                return cls.getName();
            case VAR_PACKAGE:
                return cls.getPackage().getName();
            case VAR_SUPER_CLASS:
                return cls.getSuperclass().getName();
            default:
                throw new InvalidVariableException("Unknown CLASS scope variable: " + variable);
            }
        }
    }
}
