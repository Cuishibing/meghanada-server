package meghanada.docs.declaration;

import com.google.common.base.Joiner;
import meghanada.analyze.*;
import meghanada.project.Project;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.utils.ClassNameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static meghanada.utils.FileUtils.getSource;

public class DeclarationSearcher {
    private static final Logger log = LogManager.getLogger(DeclarationSearcher.class);
    private final List<DeclarationSearchFunction> functions;
    private Project project;

    public DeclarationSearcher(final Project project) {
        this.functions = this.getFunctions();
        this.project = project;
    }

    private static Optional<Declaration> searchLocalVariable(final Source source,
                                                             final Integer line,
                                                             final Integer col,
                                                             final String symbol) {
        final EntryMessage entryMessage = log.traceEntry("line={} col={} symbol={}", line, col, symbol);
        final Optional<Variable> variable = source.getVariable(line, col);
        final Declaration declaration = variable.map(var -> {
            return new Declaration(symbol, var.fqcn, Declaration.Type.VAR, var.argumentIndex);
        }).orElseGet(() -> {
            final TypeScope ts = source.getTypeScope(line);
            if (ts == null) {
                return null;
            }
            final Variable fieldVar = ts.getField(symbol);
            if (fieldVar == null) {
                return null;
            }
            return new Declaration(symbol, fieldVar.fqcn, Declaration.Type.VAR, fieldVar.argumentIndex);
        });
        log.traceExit(entryMessage);
        return Optional.ofNullable(declaration);
    }

    public Optional<Declaration> searchDeclaration(final File file,
                                                   final int line,
                                                   final int column,
                                                   final String symbol) throws ExecutionException, IOException {

        log.trace("search symbol={}", symbol);
        return getSource(project, file).flatMap(source -> this.functions.stream()
                .map(f -> f.apply(source, line, column, symbol))
                .filter(Optional::isPresent)
                .findFirst()
                .orElse(Optional.empty()));
    }

    private List<DeclarationSearchFunction> getFunctions() {
        final List<DeclarationSearchFunction> list = new ArrayList<>(4);
        list.add(this::searchReserved);
        list.add(this::searchField);
        list.add(this::searchMethodCall);
        list.add(this::searchClassOrInterface);
        list.add(DeclarationSearcher::searchLocalVariable);
        return list;
    }

    private Optional<Declaration> searchReserved(final Source source,
                                                 final Integer line,
                                                 final Integer col,
                                                 final String symbol) {
        final EntryMessage entryMessage = log.traceEntry("line={} col={} symbol={}", line, col, symbol);
        Optional<Declaration> result = Optional.empty();
        if (symbol.equals("package") ||
                symbol.equals("import") ||
                symbol.equals("new") ||
                symbol.equals("try") ||
                symbol.equals("throw") ||
                symbol.equals("finally") ||
                symbol.equals("public") ||
                symbol.equals("private") ||
                symbol.equals("protected") ||
                symbol.equals("return") ||
                symbol.equals("static") ||
                symbol.equals("final")) {
            result = Optional.of(new Declaration(symbol, "", Declaration.Type.OTHER, 0));
        }
        log.traceExit(entryMessage);
        return result;
    }

    private Optional<Declaration> searchField(final Source source,
                                              final Integer line,
                                              final Integer col,
                                              final String symbol) {
        final EntryMessage entryMessage = log.traceEntry("line={} col={} symbol={}", line, col, symbol);
        final Optional<Declaration> result = source.searchFieldAccess(line, symbol).map(fa -> {
            String scope = fa.scope;
            if (scope != null && !scope.isEmpty()) {
                scope = scope + "." + symbol;
            } else {
                scope = symbol;
            }
            return new Declaration(scope.trim(), fa.returnType, Declaration.Type.FIELD, fa.argumentIndex);
        });
        log.traceExit(entryMessage);
        return result;
    }

    private Optional<Declaration> searchMethodCall(final Source source,
                                                   final Integer line,
                                                   final Integer col,
                                                   final String symbol) {

        final EntryMessage entryMessage = log.traceEntry("line={} col={} symbol={}", line, col, symbol);
        final Optional<MethodCall> methodCall = source.getMethodCall(line, col, true);

        final Optional<Declaration> result = methodCall.map(mc -> {
            final String methodName = mc.name;
            final List<String> arguments = mc.arguments;
            final String declaringClass = mc.declaringClass;
            final CachedASMReflector reflector = CachedASMReflector.getInstance();
            final MemberDescriptor method = reflector.reflectMethodStream(declaringClass, methodName)
                    .filter(memberDescriptor -> {
                        final List<String> parameters = memberDescriptor.getParameters();
                        return ClassNameUtils.compareArgumentType(arguments, parameters);
                    })
                    .findFirst()
                    .orElseGet(() -> reflector.reflectConstructorStream(declaringClass)
                            .filter(memberDescriptor -> {
                                final List<String> parameters = memberDescriptor.getParameters();
                                return ClassNameUtils.compareArgumentType(arguments, parameters);
                            })
                            .findFirst()
                            .orElse(null));
            String decl;
            if (method != null) {
                decl = method.getDeclaration();
            } else {
                final String args = Joiner.on(", ").join(arguments);
                decl = mc.returnType + " " + methodName + "(" + args + ")";
            }

            String scope = mc.scope;
            if (scope != null && !scope.isEmpty()) {
                scope = scope + "." + symbol;
            } else {
                scope = symbol;
            }
            return new Declaration(scope.trim(), decl, Declaration.Type.METHOD, mc.argumentIndex);
        });
        log.traceExit(entryMessage);
        return result;
    }

    private Optional<Declaration> searchClassOrInterface(final Source source,
                                                         final Integer line,
                                                         final Integer col,
                                                         final String symbol) {
        final EntryMessage entryMessage = log.traceEntry("line={} col={} symbol={}", line, col, symbol);
        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        Optional<Declaration> result = null;
        String fqcn = source.importClass.get(symbol);
        if (fqcn == null) {
            final Map<String, String> standardClasses = reflector.getStandardClasses();
            fqcn = standardClasses.get(symbol);
            if (fqcn == null) {
                if (source.packageName != null) {
                    fqcn = source.packageName + '.' + symbol;
                    result = reflector.containsClassIndex(fqcn).map(classIndex -> {
                        final Declaration declaration = new Declaration(symbol, classIndex.getReturnType(), Declaration.Type.CLASS, 0);
                        return Optional.of(declaration);
                    }).orElseGet(() -> {
                        for (final ClassScope classScope : source.getClassScopes()) {
                            final String className = classScope.getFQCN();
                            final Optional<Declaration> declaration1 = reflector.searchInnerClasses(className).stream()
                                    .filter(classIndex -> {
                                        final String returnType = classIndex.getReturnType();
                                        return returnType.endsWith(symbol);
                                    })
                                    .map(classIndex -> new Declaration(symbol,
                                            classIndex.getReturnType(),
                                            Declaration.Type.CLASS,
                                            0))
                                    .findFirst();
                            if (declaration1.isPresent()) {
                                return declaration1;
                            }
                        }
                        return Optional.empty();
                    });
                } else {
                    fqcn = symbol;
                    result = Optional.empty();
                }
            } else {
                final Declaration declaration = new Declaration(symbol, fqcn, Declaration.Type.CLASS, 0);
                result = Optional.of(declaration);
            }
        } else {
            final Declaration declaration = new Declaration(symbol, fqcn, Declaration.Type.CLASS, 0);
            result = Optional.of(declaration);
        }
        log.traceExit(entryMessage);
        return result;
    }

    @FunctionalInterface
    interface DeclarationSearchFunction {
        Optional<Declaration> apply(Source javaSource, Integer line, Integer column, String symbol);
    }

}
