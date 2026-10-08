package ac.cult.cultac.network.logging;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.logging.PacketLogText;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.Map;

public final class JavaPacketLogFormatter {
    private static final ClassValue<RecordComponent[]> COMPONENTS = new ClassValue<>() {
        @Override
        protected RecordComponent[] computeValue(Class<?> type) {
            return type.getRecordComponents();
        }
    };

    private JavaPacketLogFormatter() {}

    public static String format(
            PacketDirection direction, ConnectionPhase phase, int id, String name, PacketType<?> type, Object packet) {
        StringBuilder value = new StringBuilder();
        if (type.isOpaque()) value.append("{}");
        else append(value, packet);
        return (direction == PacketDirection.SERVERBOUND ? "IN" : "OUT") + " phase=" + phase
                + " id=" + id + " name=" + name + " type=" + type.key() + " "
                + PacketLogText.escape(value.toString());
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof SimItemStack item)
            out.append("SimItemStack[type=")
                    .append(item.itemKey())
                    .append(", count=")
                    .append(item.count())
                    .append(']');
        else if (value instanceof byte[] bytes)
            out.append("bytes[length=").append(bytes.length).append(']');
        else if (value instanceof ByteBuffer bytes)
            out.append("bytes[length=").append(bytes.remaining()).append(']');
        else if (value instanceof ByteBuf bytes)
            out.append("bytes[length=").append(bytes.readableBytes()).append(']');
        else if (value instanceof CharSequence text)
            out.append('"').append(text.toString().replace("\"", "\\\"")).append('"');
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                append(out, entry.getKey());
                out.append('=');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(", ");
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i != 0) out.append(", ");
                append(out, Array.get(value, i));
            }
            out.append(']');
        } else if (value.getClass().isRecord()) {
            out.append(value.getClass().getSimpleName()).append('[');
            boolean first = true;
            for (RecordComponent component : COMPONENTS.get(value.getClass())) {
                if (!first) out.append(", ");
                first = false;
                out.append(component.getName()).append('=');
                try {
                    append(out, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot format " + component.getName(), failure);
                }
            }
            out.append(']');
        } else out.append(value);
    }
}
