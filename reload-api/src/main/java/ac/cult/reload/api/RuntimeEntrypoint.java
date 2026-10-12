package ac.cult.reload.api;

/** Implemented by an isolated runtime, never by a native platform plugin. */
public interface RuntimeEntrypoint {
    RuntimeInstance create(HostContext context) throws Exception;
}
