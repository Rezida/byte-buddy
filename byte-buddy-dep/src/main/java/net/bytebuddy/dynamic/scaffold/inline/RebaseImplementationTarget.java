package net.bytebuddy.dynamic.scaffold.inline;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import org.objectweb.asm.MethodVisitor;

/**
 * An implementation target for redefining a given type while preserving the original methods within the
 * instrumented type.
 * <p>&nbsp;</p>
 * Super method calls are merely emulated by this {@link Implementation.Target} in order
 * to preserve Java's super call semantics a user would expect when invoking a {@code super}-prefixed method. This
 * means that original methods are either moved to renamed {@code private} methods which are never dispatched
 * virtually or they are invoked directly via the {@code INVOKESPECIAL} invocation to explicitly forbid a virtual
 * dispatch.
 */
public class RebaseImplementationTarget extends Implementation.Target.AbstractBase {

    /**
     * A method rebase resolver to be used when calling a rebased method.
     */
    protected final MethodRebaseResolver methodRebaseResolver;

    /**
     * Creates a rebase implementation target.
     *
     * @param instrumentedType     The instrumented type.
     * @param methodGraph          A method graph of the instrumented type.
     * @param methodRebaseResolver A method rebase resolver to be used when calling a rebased method.
     */
    protected RebaseImplementationTarget(TypeDescription instrumentedType, MethodGraph.Linked methodGraph, MethodRebaseResolver methodRebaseResolver) {
        super(instrumentedType, methodGraph);
        this.methodRebaseResolver = methodRebaseResolver;
    }

    @Override
    public Implementation.SpecialMethodInvocation invokeSuper(MethodDescription.Token methodToken) {
        MethodGraph.Node node = methodGraph.locate(methodToken);
        return node.getSort().isUnique()
                ? invokeSuper(node.getRepresentative())
                : Implementation.SpecialMethodInvocation.Illegal.INSTANCE;
    }

    /**
     * Invokes a method on the super type or a rebased method.
     *
     * @param methodDescription The method to be invoked.
     * @return A special method invocation for invoking the provided method.
     */
    private Implementation.SpecialMethodInvocation invokeSuper(MethodDescription methodDescription) {
        return methodDescription.getDeclaringType().equals(instrumentedType)
                ? invokeSuper(methodRebaseResolver.resolve(methodDescription.asDefined()))
                : Implementation.SpecialMethodInvocation.Simple.of(methodDescription, instrumentedType.getSuperType().asErasure());
    }

    /**
     * Defines a special method invocation on type level. This means that invoke super instructions are not explicitly
     * dispatched on the super type but on the instrumented type. This allows to call methods non-virtually even though
     * they are not defined on the super type. Redefined constructors are not renamed by are added an additional
     * parameter of a type which is only used for this purpose. Additionally, a {@code null} value is loaded onto the
     * stack when the special method invocation is applied in order to fill the operand stack with an additional caller
     * argument. Non-constructor methods are renamed.
     *
     * @param resolution A proxied super method invocation on the instrumented type.
     * @return A special method invocation on this proxied super method.
     */
    private Implementation.SpecialMethodInvocation invokeSuper(MethodRebaseResolver.Resolution resolution) {
        return resolution.isRebased()
                ? RebasedMethodInvocation.of(resolution.getResolvedMethod(), instrumentedType, resolution.getAdditionalArguments())
                : Implementation.SpecialMethodInvocation.Simple.of(resolution.getResolvedMethod(), instrumentedType);
    }

    @Override
    public TypeDescription getOriginType() {
        return instrumentedType;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || !(other == null || getClass() != other.getClass())
                && super.equals(other)
                && methodRebaseResolver.equals(((RebaseImplementationTarget) other).methodRebaseResolver);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + methodRebaseResolver.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RebaseImplementationTarget{" +
                "methodRebaseResolver=" + methodRebaseResolver +
                ", instrumentedType=" + instrumentedType +
                ", methodGraph=" + methodGraph +
                '}';
    }

    /**
     * A {@link Implementation.SpecialMethodInvocation} which invokes a rebased method
     * as given by a {@link MethodRebaseResolver}.
     */
    protected static class RebasedMethodInvocation extends Implementation.SpecialMethodInvocation.AbstractBase {

        /**
         * The method to invoke via a special method invocation.
         */
        private final MethodDescription methodDescription;

        /**
         * The instrumented type on which the method should be invoked on.
         */
        private final TypeDescription instrumentedType;

        /**
         * The stack manipulation to execute in order to invoke the rebased method.
         */
        private final StackManipulation stackManipulation;

        /**
         * Creates a new rebased method invocation.
         *
         * @param methodDescription The method to invoke via a special method invocation.
         * @param instrumentedType  The instrumented type on which the method should be invoked on.
         * @param stackManipulation The stack manipulation to execute in order to invoke the rebased method.
         */
        protected RebasedMethodInvocation(MethodDescription methodDescription, TypeDescription instrumentedType, StackManipulation stackManipulation) {
            this.methodDescription = methodDescription;
            this.instrumentedType = instrumentedType;
            this.stackManipulation = stackManipulation;
        }

        /**
         * Creates a special method invocation for the given method.
         *
         * @param resolvedMethod      The rebased method to be invoked.
         * @param instrumentedType    The instrumented type on which the method is to be invoked if it is non-static.
         * @param additionalArguments Any additional arguments that are to be provided to the rebased method.
         * @return A special method invocation of the rebased method.
         */
        protected static Implementation.SpecialMethodInvocation of(MethodDescription resolvedMethod,
                                                                   TypeDescription instrumentedType,
                                                                   StackManipulation additionalArguments) {
            StackManipulation stackManipulation = resolvedMethod.isStatic()
                    ? MethodInvocation.invoke(resolvedMethod)
                    : MethodInvocation.invoke(resolvedMethod).special(instrumentedType);
            return stackManipulation.isValid()
                    ? new RebasedMethodInvocation(resolvedMethod, instrumentedType, new Compound(additionalArguments, stackManipulation))
                    : Illegal.INSTANCE;
        }

        @Override
        public MethodDescription getMethodDescription() {
            return methodDescription;
        }

        @Override
        public TypeDescription getTypeDescription() {
            return instrumentedType;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor, Implementation.Context implementationContext) {
            return stackManipulation.apply(methodVisitor, implementationContext);
        }

        @Override
        public String toString() {
            return "RebaseImplementationTarget.RebasedMethodInvocation{" +
                    "instrumentedType=" + instrumentedType +
                    ", methodDescription=" + methodDescription +
                    ", stackManipulation=" + stackManipulation +
                    '}';
        }
    }

    /**
     * A factory for creating a {@link RebaseImplementationTarget}.
     */
    public static class Factory implements Implementation.Target.Factory {

        /**
         * The method rebase resolver to use.
         */
        private final MethodRebaseResolver methodRebaseResolver;

        /**
         * Creates a new factory for a rebase implementation target.
         *
         * @param methodRebaseResolver The method rebase resolver to use.
         */
        public Factory(MethodRebaseResolver methodRebaseResolver) {
            this.methodRebaseResolver = methodRebaseResolver;
        }

        @Override
        public Implementation.Target make(TypeDescription instrumentedType, MethodGraph.Linked methodGraph) {
            return new RebaseImplementationTarget(instrumentedType, methodGraph, methodRebaseResolver);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && methodRebaseResolver.equals(((Factory) other).methodRebaseResolver);
        }

        @Override
        public int hashCode() {
            return methodRebaseResolver.hashCode();
        }

        @Override
        public String toString() {
            return "RebaseImplementationTarget.Factory{" +
                    "methodRebaseResolver=" + methodRebaseResolver +
                    '}';
        }
    }
}