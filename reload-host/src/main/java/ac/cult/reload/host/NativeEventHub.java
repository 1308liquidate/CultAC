package ac.cult.reload.host;

import ac.cult.reload.api.HostContext;
import java.util.*;
import java.util.function.Consumer;

/** One native subscription per event/priority, with scoped generation callbacks. */
public final class NativeEventHub implements AutoCloseable {
    private final Object bus, owner;
    private final Map<Key, List<Subscription>> listeners = new LinkedHashMap<>();

    public NativeEventHub(Object bus, Object owner) {
        this.bus = bus;
        this.owner = owner;
    }

    public synchronized AutoCloseable subscribe(
            HostContext host, Class<?> event, boolean last, Consumer<Object> callback) {
        var key = new Key(event, last);
        var entries = listeners.get(key);
        if (entries == null) {
            entries = new ArrayList<>();
            listeners.put(key, entries);
            Consumer<Object> dispatch = value -> publish(key, value);
            try {
                if (last) {
                    var order = bus.getClass().getClassLoader().loadClass("org.geysermc.event.PostOrder");
                    bus.getClass()
                            .getMethod("subscribe", Object.class, Class.class, Consumer.class, order)
                            .invoke(
                                    bus,
                                    owner,
                                    event,
                                    dispatch,
                                    order.getField("LAST").get(null));
                } else
                    bus.getClass()
                            .getMethod("subscribe", Object.class, Class.class, Consumer.class)
                            .invoke(bus, owner, event, dispatch);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot register native event " + event, failure);
            }
        }
        var subscription = new Subscription(host, callback);
        entries.add(subscription);
        return () -> {
            synchronized (this) {
                listeners.get(key).remove(subscription);
            }
        };
    }

    private void publish(Key key, Object event) {
        List<Subscription> targets;
        synchronized (this) {
            targets = List.copyOf(listeners.get(key));
        }
        Object session = null;
        if (key.event.getSimpleName().startsWith("Session")) {
            try {
                session = key.event.getMethod("connection").invoke(event);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot identify Geyser session", failure);
            }
        }
        String generation = null;
        if (session != null && !targets.isEmpty()) {
            if (key.event.getSimpleName().equals("SessionInitializeEvent")) {
                for (var target : targets)
                    if (target.host.current()) {
                        target.host.pin(session);
                        break;
                    }
            }
            generation = targets.get(0).host.ownerOf(session);
        }
        for (var target : targets) {
            if (session != null && !target.host.generationId().equals(generation)) continue;
            target.host.resources().run(() -> target.callback.accept(event));
        }
    }

    @Override
    public synchronized void close() throws ReflectiveOperationException {
        listeners.clear();
        bus.getClass().getMethod("unregisterAll", Object.class).invoke(bus, owner);
    }

    private record Key(Class<?> event, boolean last) {}

    private record Subscription(HostContext host, Consumer<Object> callback) {}
}
