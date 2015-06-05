/*
 * Copyright (C) 2015 Sebastian Daschner, sebastian-daschner.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sebastian_daschner.jaxrs_analyzer.analysis.results;

import com.sebastian_daschner.jaxrs_analyzer.LogProvider;
import com.sebastian_daschner.jaxrs_analyzer.analysis.utils.JavaUtils;
import com.sebastian_daschner.jaxrs_analyzer.analysis.utils.Pair;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.TypeRepresentation;
import javassist.*;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;

import javax.json.*;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Analyzes a class (usually a POJO) for it's properties and methods.
 * The analysis is used to derive the JSON/XML representations. This class is thread-safe.
 *
 * @author Sebastian Daschner
 */
class TypeAnalyzer {

    private final static String[] NAMES_TO_IGNORE = {"getClass"};
    private static final JsonString EMPTY_JSON_STRING = new JsonString() {
        private static final String TYPE = "string";

        @Override
        public ValueType getValueType() {
            return ValueType.STRING;
        }

        @Override
        public String getString() {
            return TYPE;
        }

        @Override
        public CharSequence getChars() {
            return TYPE;
        }
    };

    private final Lock lock = new ReentrantLock();
    private String type;
    private boolean collection;

    /**
     * Analyzes the given type. Resolves known generics and creates a representation of the contained class.
     *
     * @param type The type to analyze
     * @return The type representation of the class (currently just for application/json)
     */
    TypeRepresentation analyze(final String type) {
        lock.lock();
        try {
            collection = JavaUtils.isCollection(type);
            this.type = ResponseTypeNormalizer.normalizeWrapper(type);

            if (!isRelevant())
                return new TypeRepresentation(this.type);

            // TODO analyze XML as well
            final TypeRepresentation representation = new TypeRepresentation(ResponseTypeNormalizer.normalize(this.type));
            representation.getRepresentations().put(MediaType.APPLICATION_JSON, analyzeInternal(this.type));
            return representation;
        } finally {
            lock.unlock();
        }
    }

    private boolean isRelevant() {
        // exclude java, javax, etc. packages
        return collection || !type.startsWith("java");
    }

    private static JsonValue analyzeInternal(final String type) {
        if (JavaUtils.isCollection(type)) {
            final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
            addToArray(arrayBuilder, JavaUtils.trimCollection(type));
            return arrayBuilder.build();
        }

        try {
            final CtClass ctClass = ClassPool.getDefault().get(type);

            return analyzeClass(ctClass);

        } catch (NotFoundException | ClassNotFoundException e) {
            LogProvider.error("Could not analyze class for type analysis: " + e.getMessage());
            LogProvider.debug(e);
            return Json.createObjectBuilder().build();
        }
    }

    private static JsonValue analyzeClass(final CtClass ctClass) throws ClassNotFoundException {
        if (ctClass.isEnum())
            return EMPTY_JSON_STRING;

        // TODO analyze & test inheritance

        final XmlAccessType value;
        if (ctClass.hasAnnotation(XmlAccessorType.class))
            value = ((XmlAccessorType) ctClass.getAnnotation(XmlAccessorType.class)).value();
        else
            value = XmlAccessType.PUBLIC_MEMBER;

        final List<CtField> relevantFields = Stream.of(ctClass.getDeclaredFields()).filter(f -> isRelevant(f, value)).collect(Collectors.toList());
        final List<CtMethod> relevantGetters = Stream.of(ctClass.getDeclaredMethods()).filter(m -> isRelevant(m, value)).collect(Collectors.toList());

        final JsonObjectBuilder builder = Json.createObjectBuilder();

        relevantFields.stream().map(TypeAnalyzer::mapField).filter(Objects::nonNull).forEach(p -> addToObject(builder, p.getLeft(), p.getRight()));
        relevantGetters.stream().map(TypeAnalyzer::mapGetter).filter(Objects::nonNull).forEach(p -> addToObject(builder, p.getLeft(), p.getRight()));

        return builder.build();
    }

    private static boolean isRelevant(final CtField field, final XmlAccessType accessType) {
        final int modifiers = field.getModifiers();
        if (field.hasAnnotation(XmlElement.class))
            return true;

        if (accessType == XmlAccessType.FIELD)
            // always take, unless static or transient
            return !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers) && !field.hasAnnotation(XmlTransient.class);
        else if (accessType == XmlAccessType.PUBLIC_MEMBER)
            // only for public, non-static
            return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !field.hasAnnotation(XmlTransient.class);

        return false;
    }

    /**
     * Checks if the method is public and non-static and that the method is a Getter. Does not allow methods with ignored names.
     * Does also not take methods annotated with {@link XmlTransient}
     *
     * @param method The method
     * @return {@code true} if the method should be analyzed further
     */
    private static boolean isRelevant(final CtMethod method, final XmlAccessType accessType) {
        if (!isGetter(method))
            return false;

        if (method.hasAnnotation(XmlElement.class))
            return true;

        if (accessType == XmlAccessType.PROPERTY)
            return !method.hasAnnotation(XmlTransient.class);
        else if (accessType == XmlAccessType.PUBLIC_MEMBER)
            return Modifier.isPublic(method.getModifiers()) && !method.hasAnnotation(XmlTransient.class);

        return false;
    }

    private static boolean isGetter(final CtMethod method) {
        if (Modifier.isStatic(method.getModifiers()))
            return false;

        final String name = method.getName();
        if (Stream.of(NAMES_TO_IGNORE).anyMatch(n -> n.equals(name)))
            return false;

        if (name.startsWith("get") && name.length() > 3)
            return !method.getSignature().endsWith(")V");

        return name.startsWith("is") && name.length() > 2 && method.getSignature().endsWith(")Z");
    }

    private static Pair<String, String> mapField(final CtField field) {
        try {
            final String sig = field.getGenericSignature() != null ? field.getGenericSignature() : field.getSignature();
            final String fieldType = JavaUtils.getType(SignatureAttribute.toTypeSignature(sig));
            return Pair.of(field.getName(), fieldType);
        } catch (BadBytecode e) {
            LogProvider.error("Could not analyze field: " + field);
            LogProvider.debug(e);
            return null;
        }
    }

    private static Pair<String, String> mapGetter(final CtMethod method) {
        try {
            final String sig = method.getGenericSignature() != null ? method.getGenericSignature() : method.getSignature();
            final String returnType = JavaUtils.getType(SignatureAttribute.toMethodSignature(sig).getReturnType());
            return Pair.of(normalizeGetter(method.getName()), returnType);
        } catch (BadBytecode e) {
            LogProvider.error("Could not analyze method: " + method);
            LogProvider.debug(e);
            return null;
        }
    }

    /**
     * Converts a getter name to the property name (without the "get" or "is" and lowercase).
     *
     * @param name The name of the method (MUST match "get[A-Z]+|is[A-Z]+")
     * @return The name of the property
     */
    private static String normalizeGetter(final String name) {
        final int size = name.startsWith("is") ? 2 : 3;
        final char chars[] = name.substring(size).toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    private static void addToObject(final JsonObjectBuilder builder, final String key, final String type) {
        // default for all JSR-310 classes
        if (type.startsWith("java.time")) {
            builder.add(key, "date");
            return;
        }

        switch (type) {
            case "java.lang.String":
                builder.add(key, "string");
                break;
            case "java.util.Date":
                builder.add(key, "date");
                break;
            case "java.lang.Integer":
            case "int":
            case "java.lang.Long":
            case "long":
            case "java.math.BigInteger":
                builder.add(key, 0);
                break;
            case "java.lang.Double":
            case "double":
            case "java.math.BigDecimal":
                builder.add(key, 0.0);
                break;
            case "java.lang.Boolean":
            case "boolean":
                builder.add(key, false);
                break;
            default:
                builder.add(key, analyzeInternal(type));
        }
    }

    private static void addToArray(final JsonArrayBuilder builder, final String type) {
        switch (type) {
            case "java.lang.String":
                builder.add("string");
                break;
            case "java.lang.Integer":
            case "int":
            case "java.lang.Long":
            case "long":
            case "java.math.BigInteger":
                builder.add(0);
                break;
            case "java.lang.Double":
            case "double":
            case "java.math.BigDecimal":
                builder.add(0.0);
                break;
            case "java.lang.Boolean":
            case "boolean":
                builder.add(false);
                break;
            default:
                builder.add(analyzeInternal(type));
        }
    }

}
