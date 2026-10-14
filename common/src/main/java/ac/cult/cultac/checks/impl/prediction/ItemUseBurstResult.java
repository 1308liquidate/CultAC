package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.network.protocol.ClientVersion;

/**
 * Residual movement input of one reality, captured at travel. It is evaluated against the
 * server-view use slowdown once that use is known; it never changes the legal movement.
 */
public record ItemUseBurstResult(double x, double z, double speed, float sneak, ClientVersion version) {}
