package ac.cult.reload.host;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stores only native player identities; reconnects never share a subscription entry. */
public final class AlertSubscriptions {
    private final Map<String, Set<Object>> players = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> console = new ConcurrentHashMap<>();

    public Set<Object> players(String kind) {
        return players.computeIfAbsent(kind, ignored -> new IdentitySet());
    }

    public AtomicBoolean console(String kind) {
        return console.computeIfAbsent(kind, ignored -> new AtomicBoolean());
    }

    private static final class IdentitySet extends AbstractSet<Object> {
        private final Map<Identity, Object> entries = new ConcurrentHashMap<>();

        public Iterator<Object> iterator() {
            return entries.values().iterator();
        }

        public int size() {
            return entries.size();
        }

        public boolean add(Object value) {
            return entries.putIfAbsent(new Identity(value), value) == null;
        }

        public boolean remove(Object value) {
            return entries.remove(new Identity(value)) != null;
        }

        public boolean contains(Object value) {
            return entries.containsKey(new Identity(value));
        }
    }

    private record Identity(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && value == identity.value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
