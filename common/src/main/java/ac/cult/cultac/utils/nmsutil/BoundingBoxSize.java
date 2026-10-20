package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockHorseProperties;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityInteraction;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySizeable;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.math.Vec3;

public class BoundingBoxSize {

    public static float getWidth(CultPlayer player, PacketEntity packetEntity) {
        return getWidth(packetEntity, player.getClientVersion(), player.isBedrockMovement());
    }

    public static float getWidth(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (bedrock && packetEntity.isBoat()) {
            return (float)
                    (packetEntity.bedrockBoat == null
                            ? BedrockBoatProperties.DIMENSIONS.width()
                            : packetEntity.bedrockBoat.dimensions().width());
        }
        if (bedrock && BedrockHorseProperties.supports(packetEntity.type)) {
            return 1.3965F * ((packetEntity.isBaby ? 0.5F : 1.0F) * packetEntity.scale);
        }
        return width(packetEntity, version, bedrock);
    }

    public static float getReachWidth(CultPlayer player, PacketEntity packetEntity) {
        return getWidth(player, packetEntity);
    }

    private static float getWidthMinusBaby(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        // Interaction#getDimensions: EntityDimensions.scalable(DATA_WIDTH_ID, DATA_HEIGHT_ID).
        if (packetEntity instanceof PacketEntityInteraction interaction) return interaction.actionWidth;
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            int size = size(sizeable, bedrock);
            if ((packetEntity.type == EntityTypeIds.MAGMA_CUBE) || (packetEntity.type == EntityTypeIds.SLIME)) {
                return scalesCubeMobType(version, bedrock)
                        ? baseDimensions(packetEntity).width() * size
                        : 2.04f * (0.255f * (float) size);
            }
            if ((packetEntity.type == EntityTypeIds.PHANTOM)) {
                // Phantom#getDefaultDimensions scales by 1 + 0.15 * size from 1.20.2; Bedrock keeps the older width.
                return !bedrock && version.isNewerThanOrEquals(ClientVersion.V_1_20_2)
                        ? 0.9f * (1.0f + 0.15f * size)
                        : 0.9f + size * 0.2f;
            }
        }

        String path = EntityTypeUtil.getKey(packetEntity.type).getPath();
        if (version.isOlderThan(ClientVersion.V_26_1) && path.equals("rabbit")) {
            return 0.4F;
        }
        float exactWidth = baseDimensions(packetEntity).width();
        return Float.isNaN(exactWidth) ? 0.6f : exactWidth;
    }

    public static float getWidth(PacketEntity entity, ClientVersion version) {
        return width(entity, version, false);
    }

    private static float width(PacketEntity entity, ClientVersion version, boolean bedrock) {
        float width = getWidthMinusBaby(entity, version, bedrock);
        if (entity.isBaby) {
            Float independent = independentBabyWidth(entity, version);
            width = independent == null ? width * getBabyScale(entity, version) : independent;
        }
        // AABB orders the corners, so a negative width spans the same box.
        return Math.abs(width) * entity.scale;
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, CultPlayer player) {
        return getRidingOffsetFromVehicle(entity, player.compensatedEntities.getSelf(), player);
    }

    public static Vec3 getRidingOffsetFromVehicle(PacketEntity entity, PacketEntity passenger, CultPlayer player) {
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        return getRidingOffsetFromVehicle(entity, passenger, player, box);
    }

    public static Vec3 getRidingOffsetFromVehicle(
            PacketEntity entity, PacketEntity passenger, CultPlayer player, SimpleCollisionBox box) {
        return getRidingOffsetFromVehicle(
                entity, passenger, player, box, entity.passengers.indexOf(passenger), entity.passengers.size());
    }

    public static Vec3 getRidingOffsetFromVehicle(
            PacketEntity entity,
            PacketEntity passenger,
            CultPlayer player,
            SimpleCollisionBox box,
            int passengerIndex,
            int passengerCount) {
        double x = (box.maxX + box.minX) / 2.0;
        double y = box.minY;
        double z = (box.maxZ + box.minZ) / 2.0;

        Vec3 passengerAttachment;
        if (entity instanceof PacketEntityTrackXRot xRotEntity && EntityTypeUtil.isBoat(entity.type)) {
            passengerAttachment = getBoatPassengerAttachmentPoint(
                    entity, passenger, xRotEntity.interpYaw, passengerIndex, passengerCount);
        } else {
            passengerAttachment = getDefaultPassengerAttachmentPoint(entity, passengerIndex);
        }

        // Vanilla Entity#positionRider subtracts the rider's current, scaled attachment
        // after the vehicle supplies its passenger point, including for stationary seats.
        Vec3 vehicleAttachment =
                nativeVector(baseDimensions(passenger).vehicleAttachment(passenger.scale, getAttachmentYaw(passenger)));
        return new Vec3(x, y, z).add(passengerAttachment).subtract(vehicleAttachment);
    }

    private static float getAttachmentYaw(PacketEntity entity) {
        return entity instanceof PacketEntityTrackXRot xRotEntity ? xRotEntity.interpYaw : entity.clientPhysicalYaw;
    }

    private static Vec3 getDefaultPassengerAttachmentPoint(PacketEntity vehicle, int passengerIndex) {
        int index = Math.max(passengerIndex, 0);
        float vehicleYaw = getAttachmentYaw(vehicle);
        Vec3 passengerAttachment =
                nativeVector(baseDimensions(vehicle).passengerAttachment(index, vehicle.scale, vehicleYaw));
        if (vehicle instanceof PacketEntityHorse horse) {
            // MCP-Reborn AbstractHorse#getPassengerAttachmentPoint adds this rearing offset to the default point.
            passengerAttachment = passengerAttachment.add(
                    new Vec3(0.0D, 0.15D * horse.standAnimO * vehicle.scale, -0.7D * horse.standAnimO * vehicle.scale)
                            .yRot(-vehicleYaw * ((float) Math.PI / 180F)));
        } else if (vehicle instanceof PacketEntityStrider strider) {
            // MCP-Reborn Strider#getPassengerAttachmentPoint adds a client-only
            // walk-animation Y offset before passenger positioning.
            passengerAttachment = passengerAttachment.add(
                    0.0D, (float) strider.getPassengerAttachmentYOffset() * vehicle.scale, 0.0D);
        }
        return passengerAttachment;
    }

    private static Vec3 getBoatPassengerAttachmentPoint(
            PacketEntity vehicle, PacketEntity passenger, float vehicleYaw, int passengerIndex, int passengerCount) {
        int index = Math.max(passengerIndex, 0);
        float zOffset = getSingleBoatPassengerOffset(vehicle);

        if (passengerCount > 1) {
            zOffset = index == 0 ? 0.2F : -0.6F;
            if (passenger.isAnimal()) {
                zOffset += 0.2F;
            }
        }

        double rideHeight = isRaft(vehicle) ? getHeight(vehicle) * 0.8888889F : getHeight(vehicle) / 3.0F;
        return new Vec3(0.0D, rideHeight, zOffset).yRot(-vehicleYaw * ((float) Math.PI / 180F));
    }

    private static float getSingleBoatPassengerOffset(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_chest_boat") || path.endsWith("_chest_raft") ? 0.15F : 0.0F;
    }

    private static boolean isRaft(PacketEntity vehicle) {
        String path = EntityTypeUtil.getKey(vehicle.type).getPath();
        return path.endsWith("_raft") || path.endsWith("_chest_raft");
    }

    public static float getHeight(CultPlayer player, PacketEntity packetEntity) {
        return getHeight(packetEntity, player.getClientVersion(), player.isBedrockMovement());
    }

    public static float getHeight(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (bedrock && packetEntity.isBoat()) {
            return (float)
                    (packetEntity.bedrockBoat == null
                            ? BedrockBoatProperties.DIMENSIONS.height()
                            : packetEntity.bedrockBoat.dimensions().height());
        }
        if (bedrock && BedrockHorseProperties.supports(packetEntity.type)) {
            return 1.6F * ((packetEntity.isBaby ? 0.5F : 1.0F) * packetEntity.scale);
        }
        return height(packetEntity, version, bedrock);
    }

    public static float getReachHeight(CultPlayer player, PacketEntity packetEntity) {
        return getHeight(player, packetEntity);
    }

    public static float getHeight(PacketEntity packetEntity) {
        return getHeight(packetEntity, ClientVersion.V_26_2);
    }

    private static float getHeightMinusBaby(PacketEntity packetEntity, ClientVersion version, boolean bedrock) {
        if (packetEntity instanceof PacketEntityInteraction interaction) return interaction.actionHeight;
        if (packetEntity instanceof PacketEntitySizeable sizeable) {
            int size = size(sizeable, bedrock);
            if ((packetEntity.type == EntityTypeIds.MAGMA_CUBE) || (packetEntity.type == EntityTypeIds.SLIME)) {
                return scalesCubeMobType(version, bedrock)
                        ? baseDimensions(packetEntity).height() * size
                        : 2.04f * (0.255f * (float) size);
            }
            if (packetEntity.type == EntityTypeIds.PHANTOM
                    && !bedrock
                    && version.isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
                return 0.5f * (1.0f + 0.15f * size);
            }
        }

        String path = EntityTypeUtil.getKey(packetEntity.type).getPath();
        if (version.isOlderThan(ClientVersion.V_26_1) && path.equals("rabbit")) {
            return 0.5F;
        }
        float exactHeight = baseDimensions(packetEntity).height();
        return Float.isNaN(exactHeight) ? 1.95f : exactHeight;
    }

    public static float getHeight(PacketEntity entity, ClientVersion version) {
        return height(entity, version, false);
    }

    private static float height(PacketEntity entity, ClientVersion version, boolean bedrock) {
        float height = getHeightMinusBaby(entity, version, bedrock);
        if (entity.isBaby) {
            Float independent = independentBabyHeight(entity, version);
            height = independent == null ? height * getBabyScale(entity, version) : independent;
        }
        return Math.abs(height) * entity.scale;
    }

    /** Whether the entity's height is negative, which {@code AABB} spans below its position. */
    public static boolean spansBelowPosition(CultPlayer player, PacketEntity entity) {
        return (entity instanceof PacketEntityInteraction || entity instanceof PacketEntitySizeable)
                && getHeightMinusBaby(entity, player.getClientVersion(), player.isBedrockMovement()) < 0;
    }

    /** From 1.20.5 AbstractCubeMob#getDefaultDimensions scales the type's 0.52 by the size. */
    private static boolean scalesCubeMobType(ClientVersion version, boolean bedrock) {
        return !bedrock && version.isNewerThanOrEquals(ClientVersion.V_1_20_5);
    }

    /** The size {@code getDefaultDimensions} scales by. Bedrock sizing keeps a floor of 1. */
    private static int size(PacketEntitySizeable sizeable, boolean bedrock) {
        return bedrock ? Math.max(sizeable.size, 1) : sizeable.size;
    }

    private static float getBabyScale(PacketEntity entity, ClientVersion version) {
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (path.equals("turtle")) return 0.3F;
        if (path.equals("happy_ghast")) return 0.2375F;
        if (path.equals("dolphin")) return 0.65F;
        if (path.equals("armadillo")) return 0.6F;
        if (EntityTypeUtil.isCamelFamily(entity.type))
            return version.isNewerThanOrEquals(ClientVersion.V_26_1) ? 0.6F : 0.45F;
        if (version.isNewerThanOrEquals(ClientVersion.V_26_1)) {
            if (path.equals("goat")) return 0.55F;
            if (EntityTypeUtil.isHorseFamily(entity.type)) return 0.7F;
        }
        return 0.5F;
    }

    private static Float independentBabyWidth(PacketEntity entity, ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_26_1)) return null;
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) {
            switch (path) {
                case "axolotl":
                    return 0.375F;
                case "hoglin", "zoglin":
                    return 0.75F;
                case "fox":
                    return 0.36F;
                case "goat":
                    return 0.45F;
                case "camel", "camel_husk":
                    return 0.95F;
            }
        }
        return switch (path) {
            case "axolotl", "squid", "glow_squid" -> 0.5F;
            case "chicken" -> 0.3F;
            case "rabbit" -> 0.24F;
            case "zombie",
                    "drowned",
                    "husk",
                    "zombie_villager",
                    "zombified_piglin",
                    "villager",
                    "piglin",
                    "piglin_brute" -> 0.49F;
            default -> null;
        };
    }

    private static Float independentBabyHeight(PacketEntity entity, ClientVersion version) {
        if (version.isOlderThan(ClientVersion.V_26_1)) return null;
        String path = EntityTypeUtil.getKey(entity.type).getPath();
        if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) {
            switch (path) {
                case "axolotl":
                    return 0.21F;
                case "hoglin", "zoglin":
                    return 0.85F;
                case "fox":
                    return 0.42F;
                case "goat":
                    return 0.65F;
                case "camel", "camel_husk":
                    return 1.4F;
                case "zombie",
                        "drowned",
                        "husk",
                        "zombie_villager",
                        "zombified_piglin",
                        "villager",
                        "piglin",
                        "piglin_brute":
                    return 0.98F;
            }
        }
        return switch (path) {
            case "axolotl" -> 0.25F;
            case "chicken", "rabbit" -> 0.4F;
            case "squid", "glow_squid" -> 0.63F;
            case "zombie",
                    "drowned",
                    "husk",
                    "zombie_villager",
                    "zombified_piglin",
                    "villager",
                    "piglin",
                    "piglin_brute" -> 0.99F;
            default -> null;
        };
    }

    private static EntityTypes.Type baseDimensions(PacketEntity packetEntity) {
        return EntityTypeUtil.modelType(packetEntity.type);
    }

    private static Vec3 nativeVector(ac.cult.blocksim.engine.Vec3 point) {
        return new Vec3(point.x(), point.y(), point.z());
    }
}
