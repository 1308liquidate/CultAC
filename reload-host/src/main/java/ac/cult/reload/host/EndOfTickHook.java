package ac.cult.reload.host;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One native list wrapper, independent of the lifetime of any engine generation. */
public final class EndOfTickHook extends AbstractList<Object> implements AutoCloseable {
    private final Object connection;
    private final Field field;
    private final List<Object> delegate;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Object> installed;
    private final sun.misc.Unsafe unsafe;

    @SuppressWarnings("unchecked")
    public EndOfTickHook(Object connection, Field field) throws ReflectiveOperationException {
        this.connection = connection;
        this.field = field;
        delegate = (List<Object>) field.get(connection);
        var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        unsafe = (sun.misc.Unsafe) access.get(null);
        installed = Collections.synchronizedList(this);
        unsafe.putObject(connection, unsafe.objectFieldOffset(field), installed);
    }

    public AutoCloseable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public Object get(int index) {
        return delegate.get(index);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Object set(int index, Object value) {
        return delegate.set(index, value);
    }

    @Override
    public void add(int index, Object value) {
        delegate.add(index, value);
    }

    @Override
    public Object remove(int index) {
        return delegate.remove(index);
    }

    @Override
    public Iterator<Object> iterator() {
        for (var listener : listeners) {
            try {
                listener.run();
            } catch (Throwable failure) {
                java.util.logging.Logger.getLogger("CultAC")
                        .log(java.util.logging.Level.SEVERE, "CultAC tick callback failed", failure);
            }
        }
        return delegate.iterator();
    }

    @Override
    public void close() throws IllegalAccessException {
        listeners.clear();
        if (field.get(connection) == installed) unsafe.putObject(connection, unsafe.objectFieldOffset(field), delegate);
    }
}
