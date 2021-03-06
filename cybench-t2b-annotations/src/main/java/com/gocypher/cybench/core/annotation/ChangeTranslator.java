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

package com.gocypher.cybench.core.annotation;

import java.lang.annotation.Annotation;
import java.util.LinkedList;
import java.util.List;

import javax.lang.model.element.Element;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.generators.core.TestScopeBenchmarkGenerator;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacFiler;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Name;

public class ChangeTranslator extends TreeTranslator {
    private final CreateStatement createStatement;
    private final JavacProcessingEnvironment env;
    private LinkedList<Symbol.ClassSymbol> processed = new LinkedList<>();

    public ChangeTranslator(JavacProcessingEnvironment javacProcessingEnvironment, TreeMaker treeMaker) {
        createStatement = new CreateStatement(new GetElement(javacProcessingEnvironment), treeMaker);
        env = javacProcessingEnvironment;
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl jcClassDecl) {
        super.visitClassDef(jcClassDecl);
        if (!isContainsAnnotation(jcClassDecl.getModifiers()) && notContainBenchmark(jcClassDecl)
                && containsTest(jcClassDecl)) {
            // result is placed into the AST, replacing the current variable declaration.
            result = createStatement.apply(jcClassDecl);
            if (result != null && !((JavacFiler) env.getFiler()).newFiles()) {
                ((JavacFiler) env.getFiler()).getGeneratedSourceNames()
                        .add("cyBenchDummy" + System.currentTimeMillis());
            }
            processed.add(jcClassDecl.sym);
        }
    }

    private boolean containsTest(JCTree.JCClassDecl jcClassDecl) {
        return jcClassDecl.getMembers().stream().filter(e -> e.getKind() == Tree.Kind.METHOD)
                .map(m -> (JCTree.JCMethodDecl) m)
                .anyMatch(m -> containsAnnotation(m.getModifiers(), TestScopeBenchmarkGenerator.BENCHMARK_ANNOTATIONS)); // FIXME
                                                                                                                         // please
    }

    private boolean notContainBenchmark(JCTree.JCClassDecl jcClassDecl) {
        return jcClassDecl.getMembers().stream().filter(e -> e.getKind() == Tree.Kind.METHOD)
                .map(m -> (JCTree.JCMethodDecl) m)
                .noneMatch(m -> containsAnnotation(m.getModifiers(), Benchmark.class));
    }

    private boolean isContainsAnnotation(JCTree.JCModifiers modifiers) {
        return containsAnnotation(modifiers, State.class);
    }

    private boolean containsAnnotation(JCTree.JCModifiers modifiers, Class<? extends Annotation>... aClasses) {
        if (aClasses != null) {
            for (Class<? extends Annotation> aClass : aClasses) {
                boolean match = modifiers.getAnnotations().stream().anyMatch(a -> nameIs(aClass, a));

                if (match) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean nameIs(Class<?> name, JCTree.JCAnnotation a) {
        return a.getAnnotationType().toString().equals(name.getName())
                || a.getAnnotationType().toString().equals(name.getSimpleName());
    }

    public LinkedList<Symbol.ClassSymbol> getProcessed() {
        return processed;
    }

    public static class GetElement {
        private final JavacProcessingEnvironment javacProcessingEnvironment;

        public GetElement(JavacProcessingEnvironment javacProcessingEnvironment) {
            this.javacProcessingEnvironment = javacProcessingEnvironment;
        }

        public Symbol apply(Class<?> javaClass) {
            return (Symbol) getPackageElements(javaClass).stream()
                    .filter(element -> element.getSimpleName().toString().equals(javaClass.getSimpleName())).findAny()
                    .orElseThrow(() -> new AssertionError("Unable to get " + javaClass));
        }

        public Symbol apply(Enum<?> javaEnum) {
            return (Symbol) getClassElements(javaEnum.getDeclaringClass()).stream()
                    .filter(s -> s.getSimpleName().contentEquals("Benchmark")).findAny()
                    .orElseThrow(() -> new AssertionError("Unable to get " + javaEnum));
        }

        private List<? extends Element> getPackageElements(Class<?> javaClass) {
            return javacProcessingEnvironment.getElementUtils().getPackageElement(javaClass.getPackage().getName())
                    .getEnclosedElements();
        }

        private List<? extends Element> getClassElements(Class<?> javaClass) {
            return javacProcessingEnvironment.getElementUtils().getTypeElement(javaClass.getName())
                    .getEnclosedElements();
        }

    }

    public static class CreateStatement {
        private final GetElement getElement;
        private final TreeMaker treeMaker;

        public CreateStatement(GetElement getElement, TreeMaker treeMaker) {
            this.getElement = getElement;
            this.treeMaker = treeMaker;
        }

        // JCModifiers var1, Name var2, List<JCTypeParameter> var3, JCExpression var4, List<JCExpression> var5,
        // List<JCTree> var6

        public JCTree.JCClassDecl apply(JCTree.JCClassDecl classDeclaration) {
            JCTree.JCModifiers modifiers1 = classDeclaration.getModifiers();

            com.sun.tools.javac.util.List<JCTree.JCExpression> symbols = com.sun.tools.javac.util.List
                    .of(treeMaker.QualIdent(getElement.apply(Scope.Benchmark)));
            JCTree.JCAnnotation annotation = treeMaker
                    .TypeAnnotation(treeMaker.QualIdent(getElement.apply(State.class)), symbols);
            modifiers1.annotations = modifiers1.annotations.append(annotation);

            Name name = classDeclaration.getSimpleName();
            com.sun.tools.javac.util.List<JCTree.JCTypeParameter> typeParameters = classDeclaration.getTypeParameters();
            JCTree.JCExpression extendsClause = classDeclaration.getExtendsClause();
            com.sun.tools.javac.util.List<JCTree.JCExpression> implementsClause = classDeclaration
                    .getImplementsClause();
            com.sun.tools.javac.util.List<JCTree> members = classDeclaration.getMembers();

            JCTree.JCClassDecl newVariableDeclaration = treeMaker.ClassDef(modifiers1, name, typeParameters,
                    extendsClause, implementsClause, members);
            return newVariableDeclaration;
        }
    }
}
