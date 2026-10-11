package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import org.junit.jupiter.api.Test;

class CameraEyeHeightTest {
    @Test
    void remotePlayerCameraUsesItsOwnPoseAndFloatScale() {
        var camera = new PacketEntity(EntityTypeIds.PLAYER, 1);
        camera.scale = 2;
        camera.actionPose = EntityPose.CROUCHING;
        assertEquals(1.27F * 2, CameraEyeHeight.of(camera, ClientVersion.V_26_2));
        camera.actionPose = EntityPose.SWIMMING;
        assertEquals(.4F * 2, CameraEyeHeight.of(camera, ClientVersion.V_26_2));
        camera.actionPose = EntityPose.SLEEPING;
        assertEquals(.2F, CameraEyeHeight.of(camera, ClientVersion.V_26_2));
        camera.actionPose = EntityPose.DYING;
        assertEquals(1.62F, CameraEyeHeight.of(camera, ClientVersion.V_26_2));
    }

    @Test
    void babyCowUsesItsIndependentNativeEyeHeight() {
        var camera = new PacketEntity(EntityTypeIds.COW, 1);
        camera.isBaby = true;
        camera.scale = .75F;
        assertEquals(.69F * .75F, CameraEyeHeight.of(camera, ClientVersion.V_26_2));
    }
}
