package ac.cult.reload.host;

import java.lang.reflect.*;
import java.util.*;

/** Public API wrappers belong to the bootstrap and sever delegates when a generation retires. */
final class HostApiRouter {
    private final GenerationManager manager;
    private final IdentityHashMap<Object, Handle> handles = new IdentityHashMap<>();
    private final Map<String, Object> services = new HashMap<>();
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private Object facade;

    HostApiRouter(GenerationManager manager) {
        this.manager = manager;
    }

    synchronized Object facade() {
        if (facade == null) {
            try {
                var contract = manager.parentLoader().loadClass("ac.grim.grimac.api.GrimAbstractAPI");
                facade = Proxy.newProxyInstance(
                        contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                            if (method.getName().equals("getCurrentTick")) return manager.currentTick();
                            if (method.getName().equals("reload")
                                    || method.getName().equals("reloadAsync")) {
                                Object source = args == null || args.length == 0 ? null : args[0];
                                if (source == services.get("getConfigManager")) source = null;
                                var result = manager.reload(source);
                                if (method.getName().equals("reloadAsync")) return result;
                                result.join();
                                return null;
                            }
                            if (method.getName().equals("getGrimUser")) {
                                return lookup(args[0]);
                            }
                            if (method.getName().equals("getAlertManager")
                                    || method.getName().equals("getConfigManager")) {
                                synchronized (this) {
                                    return services.computeIfAbsent(
                                            method.getName(),
                                            ignored -> Proxy.newProxyInstance(
                                                    method.getReturnType().getClassLoader(),
                                                    new Class<?>[] {method.getReturnType()},
                                                    (service, operation, arguments) -> {
                                                        if (operation.getDeclaringClass() == Object.class)
                                                            return objectMethod(service, operation, arguments);
                                                        if (method.getName().equals("getConfigManager")
                                                                && operation
                                                                        .getName()
                                                                        .equals("reload")) {
                                                            manager.reload(null).join();
                                                            return null;
                                                        }
                                                        String owner = argumentsOwner(arguments);
                                                        if (owner == null) owner = manager.currentId();
                                                        final String selected = owner;
                                                        return manager.call(selected, runtime -> {
                                                            Object target = method.invoke(runtime.api());
                                                            return invoke(selected, target, operation, arguments, null);
                                                        });
                                                    }));
                                }
                            }
                            if (method.getName().equals("registerVariable")
                                    || method.getName().equals("registerFunction")) {
                                synchronized (registrations) {
                                    String key = method.getName() + ":" + method.getParameterTypes()[1].getName() + ":"
                                            + args[0];
                                    for (var owner : manager.runtimeIds()) register(owner, method, args);
                                    if (args[1] == null) registrations.remove(key);
                                    else registrations.put(key, new Registration(method, args.clone()));
                                }
                                return null;
                            }
                            String owner = manager.currentId();
                            return manager.call(owner, runtime -> invoke(owner, runtime.api(), method, args, null));
                        });
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Missing shared GrimAPI", failure);
            }
        }
        return facade;
    }

    private synchronized Object lookup(Object identity) {
        var user = manager.user(identity);
        return user == null ? null : wrap(user.owner(), user.user(), user.user());
    }

    synchronized Object wrap(String owner, Object value, Object suppliedUser) {
        final Object user = suppliedUser == null && value instanceof ac.grim.grimac.api.GrimUser ? value : suppliedUser;
        if (value == null) return null;
        if (value instanceof java.util.function.Supplier<?> supplier)
            return new TextSupplier(String.valueOf(supplier.get()));
        if (value instanceof Set<?> values) {
            var result = new LinkedHashSet<Object>();
            for (var element : values) result.add(wrap(owner, element, user));
            return Collections.unmodifiableSet(result);
        }
        if (value instanceof Collection<?> values)
            return values.stream().map(element -> wrap(owner, element, user)).toList();
        if (value instanceof Map<?, ?> values) {
            var result = new LinkedHashMap<Object, Object>();
            values.forEach((key, element) -> result.put(key, wrap(owner, element, user)));
            return Collections.unmodifiableMap(result);
        }
        if (value.getClass().getClassLoader() == manager.parentLoader()) return value;
        var handle = handles.get(value);
        if (handle != null) {
            if (handle.user == null && user != null) handle.user = user;
            return handle.proxy;
        }
        var contracts = new LinkedHashSet<Class<?>>();
        collect(value.getClass(), contracts);
        if (value instanceof java.util.function.Function<?, ?>) contracts.add(java.util.function.Function.class);
        if (contracts.isEmpty()) {
            if (value.getClass().getClassLoader() instanceof RuntimeClassLoader)
                throw new IllegalArgumentException("Implementation objects cannot cross the public API boundary");
            return value;
        }
        handle = new Handle(owner, value, user, contracts.toArray(Class<?>[]::new));
        handles.put(value, handle);
        return handle.proxy;
    }

    private static void collect(Class<?> type, Set<Class<?>> interfaces) {
        if (type == null) return;
        for (var contract : type.getInterfaces()) {
            if (contract.getName().startsWith("ac.grim.grimac.api.") && Modifier.isPublic(contract.getModifiers()))
                interfaces.add(contract);
            collect(contract, interfaces);
        }
        collect(type.getSuperclass(), interfaces);
    }

    Object unwrap(Object value) {
        if (value != null
                && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof Handle handle) {
            Object target = handle.target;
            if (target == null) throw RuntimeFailures.ended();
            return target;
        }
        return value;
    }

    synchronized void detach(String owner) {
        handles.entrySet().removeIf(entry -> {
            if (!Objects.equals(entry.getValue().owner, owner)) return false;
            entry.getValue().owner = null;
            entry.getValue().target = null;
            entry.getValue().user = null;
            return true;
        });
    }

    synchronized void detachUser(Object user) {
        handles.entrySet().removeIf(entry -> {
            if (entry.getValue().user != user) return false;
            entry.getValue().owner = null;
            entry.getValue().target = null;
            entry.getValue().user = null;
            return true;
        });
    }

    void initialize(String owner) throws Exception {
        synchronized (registrations) {
            for (var registration : registrations.values())
                register(owner, registration.method, registration.arguments);
        }
    }

    private void register(String owner, Method method, Object[] arguments) throws Exception {
        var copied = arguments.clone();
        if (copied[1] instanceof java.util.function.Function<?, ?> function) {
            @SuppressWarnings("unchecked")
            var callback = (java.util.function.Function<Object, Object>) function;
            copied[1] = (java.util.function.Function<Object, Object>) value -> callback.apply(wrap(owner, value, null));
        }
        manager.call(owner, runtime -> method.invoke(runtime.api(), copied));
    }

    private String argumentsOwner(Object[] arguments) {
        String owner = null;
        if (arguments != null)
            for (Object argument : arguments) {
                if (argument != null
                        && Proxy.isProxyClass(argument.getClass())
                        && Proxy.getInvocationHandler(argument) instanceof Handle handle) {
                    if (handle.owner == null) throw RuntimeFailures.ended();
                    if (owner != null && !owner.equals(handle.owner))
                        throw new IllegalArgumentException("API arguments belong to different runtimes");
                    owner = handle.owner;
                }
            }
        return owner;
    }

    private Object invoke(String owner, Object target, Method method, Object[] arguments, Object user)
            throws Exception {
        Object[] args = arguments;
        if (arguments != null) {
            for (int i = 0; i < arguments.length; i++) {
                Object value = unwrap(arguments[i]);
                if (value != arguments[i]) {
                    if (args == arguments) args = arguments.clone();
                    args[i] = value;
                }
            }
        }
        final Object[] values = args;
        try {
            return manager.call(owner, ignored -> wrap(owner, method.invoke(target, values), user));
        } catch (InvocationTargetException failure) {
            throw RuntimeFailures.detached(failure.getCause());
        } catch (RuntimeException failure) {
            throw RuntimeFailures.detached(failure);
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "CultAC API handle";
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private final class Handle implements InvocationHandler {
        volatile String owner;
        volatile Object target;
        volatile Object user;
        final Object proxy;

        Handle(String owner, Object target, Object user, Class<?>[] contracts) {
            this.owner = owner;
            this.target = target;
            this.user = user;
            proxy = Proxy.newProxyInstance(manager.parentLoader(), contracts, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            Object delegate = target;
            if (delegate == null) throw RuntimeFailures.ended();
            return HostApiRouter.this.invoke(owner, delegate, method, args, user);
        }
    }

    private record TextSupplier(String text) implements java.util.function.Supplier<String> {
        public String get() {
            return text;
        }
    }

    private record Registration(Method method, Object[] arguments) {}
}
