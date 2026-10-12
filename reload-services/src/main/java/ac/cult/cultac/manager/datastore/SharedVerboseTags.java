package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.storage.verbose.VerboseSchema;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import java.util.*;

/** Shared renderers capture strings and numbers, never engine enum classes or model tables. */
public final class SharedVerboseTags {
    private static final Map<String, List<String>> catalogs = new HashMap<>();

    private SharedVerboseTags() {}

    /** GrimAPI 1.6 caches declaration owners strongly; release only this stopped runtime. */
    public static void releaseDeclarations(ClassLoader owner) throws ReflectiveOperationException {
        var field = ac.grim.grimac.api.storage.verbose.Verbose.class.getDeclaredField("DECLARED_BY_CLASS");
        field.setAccessible(true);
        var declarations = (Map<?, ?>) field.get(null);
        declarations.keySet().removeIf(key -> key instanceof Class<?> type && type.getClassLoader() == owner);
    }

    private static synchronized void compatible(String name, List<String> values) {
        var previous = catalogs.putIfAbsent(name, List.copyOf(values));
        if (previous != null && !previous.equals(values))
            throw new IllegalStateException("Verbose catalog " + name + " requires a bootstrap compatibility update");
    }

    public static void registerEnum(String name, Enum<?>[] values, boolean lower) {
        List<String> labels = Arrays.stream(values).map(Enum::name).toList();
        compatible(name, labels);
        VerboseTags.register(name, List.of(VerboseSchema.TypeTag.VI), (in, ctx, out, fmt) -> {
            int id = in.rvi();
            String text = id == 0 ? "null" : id > 0 && id <= labels.size() ? labels.get(id - 1) : "unknown(" + id + ")";
            out.append(lower ? text.toLowerCase(Locale.ROOT) : text);
        });
    }

    public static void register(String name, List<String> labels, boolean unsigned, String negative, String missing) {
        labels = List.copyOf(labels);
        compatible(name, labels);
        var names = labels;
        VerboseTags.register(
                name, List.of(unsigned ? VerboseSchema.TypeTag.VI : VerboseSchema.TypeTag.ZZ), (in, ctx, out, fmt) -> {
                    int id = unsigned ? in.rvi() : in.rzz();
                    out.append(
                            id < 0
                                    ? negative
                                    : id < names.size()
                                            ? names.get(id)
                                            : missing == null ? "unknown(" + id + ")" : missing);
                });
    }

    public enum Primitive {
        PACKET,
        OFFSET,
        NUMBER
    }

    public static void register(String name, Primitive primitive) {
        if (primitive == Primitive.PACKET) {
            VerboseTags.register(
                    name, List.of(VerboseSchema.TypeTag.STR), (in, ctx, out, fmt) -> out.append(in.rstr()));
        } else {
            VerboseTags.register(
                    name,
                    List.of(VerboseSchema.TypeTag.F64),
                    (in, ctx, out, fmt) -> out.append(number(in.rf64(), primitive == Primitive.NUMBER)));
        }
    }

    private static String number(double value, boolean standard) {
        if (standard && Math.abs(value) < 1e-7) return "0";
        if ((standard ? Math.abs(value) : value) < 0.001)
            return String.format("%.4E", value).replace("E-0", "E-");
        return String.format("%6f", value).replace("0.", ".");
    }
}
