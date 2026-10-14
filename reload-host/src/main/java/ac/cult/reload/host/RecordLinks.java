package ac.cult.reload.host;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.runtime.ObjectMethods;

/** Defined by {@link RecordMethodCaches} in its own loader; never load it through the bootstrap loader. */
public final class RecordLinks {
    private RecordLinks() {}

    public interface Part {}

    record Shape(Part part, Object any, int number) {}

    /** Links equals, hashCode and toString over record, interface, object and int components. */
    public static void relink() throws Throwable {
        var lookup = MethodHandles.lookup();
        MethodHandle[] getters = {
            lookup.findGetter(Shape.class, "part", Part.class),
            lookup.findGetter(Shape.class, "any", Object.class),
            lookup.findGetter(Shape.class, "number", int.class)
        };
        String names = "part;any;number";
        ObjectMethods.bootstrap(
                lookup,
                "equals",
                MethodType.methodType(boolean.class, Shape.class, Object.class),
                Shape.class,
                names,
                getters);
        ObjectMethods.bootstrap(
                lookup, "hashCode", MethodType.methodType(int.class, Shape.class), Shape.class, names, getters);
        ObjectMethods.bootstrap(
                lookup, "toString", MethodType.methodType(String.class, Shape.class), Shape.class, names, getters);
    }
}
