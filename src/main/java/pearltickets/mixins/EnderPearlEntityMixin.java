package pearltickets.mixins;

// Minecraft
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

// Mixin
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Java
import java.io.IOException;
import java.lang.Math;
import java.util.Comparator;


@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlEntityMixin extends ThrownItemEntity {
    private static final int MIN_Y = 0;
    private static final ChunkTicketType<ChunkPos> ENDER_PEARL_TICKET =
            ChunkTicketType.create("ender_pearl", Comparator.comparingLong(ChunkPos::toLong), 2);

    private boolean sync = true;
    private Vec3d realPos = null;
    private Vec3d realVelocity = null;

    protected EnderPearlEntityMixin(EntityType<? extends ThrownItemEntity> entityType, World world) {
        super(entityType, world);
    }

    private static boolean isEntityTickingChunk(WorldChunk chunk) {
        return (chunk != null && chunk.getLevelType() == ChunkHolder.LevelType.ENTITY_TICKING);
    }

    private static int getHighestMotionBlockingY(NbtCompound nbtCompound) {
        int highestY = MIN_Y;
        if (nbtCompound != null) {
            // vanilla 1.16+
            for (long element : nbtCompound.getCompound("Level")
                    .getCompound("Heightmaps").getLongArray("MOTION_BLOCKING")) {
                // 64 bits in long, 7 y-values * 9-bit-each = 63 bits, and 1 bit vacant.
                for (int i = 0; i < 7; i++) {
                    int y = (int)(element & 0b111111111);
                    if (y > highestY) highestY = y;
                    element = element >> 9;
                }
            }
        }
        return highestY;
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void preprocess(CallbackInfo ci) {
        this.skippyChunkLoading();
    }

    // preprocess main function
    private void skippyChunkLoading() {
        World world = this.getEntityWorld();

        if (world instanceof ServerWorld) {
            Vec3d currPos = this.getPos().add(Vec3d.ZERO);
            Vec3d currVelocity = this.getVelocity().add(Vec3d.ZERO);

            if (this.sync) {
                this.realPos = currPos;
                this.realVelocity = currVelocity;
            }

            // next pos
            Vec3d nextPos = this.realPos.add(this.realVelocity);
            Vec3d nextVelocity = this.realVelocity.multiply(0.99F).subtract(0, this.getGravity(), 0);

            // debug
            // System.out.println("curr: " + currPos + currVelocity);
            // System.out.println("real: " + this.realPos + this.realVelocity);
            // System.out.println("next: " + nextPos + nextVelocity);

            // chunkPos to temporarily store pearl and real chunkPos to check chunk loading
            ChunkPos currChunkPos = new ChunkPos((int)Math.floor(currPos.x) >> 4, (int)Math.floor(currPos.z) >> 4);
            ChunkPos realChunkPos = new ChunkPos((int)Math.floor(this.realPos.x) >> 4, (int)Math.floor(this.realPos.z) >> 4);
            ChunkPos nextChunkPos = new ChunkPos((int)Math.floor(nextPos.x) >> 4, (int)Math.floor(nextPos.z) >> 4);

            // debug
            // System.out.printf("currChunkPos: (%d, %d)\t realChunkPos: (%d, %d)\t nextChunkPos: (%d, %d)\n",
            //         currChunkPos.x, currChunkPos.z, realChunkPos.x, realChunkPos.z, nextChunkPos.x, nextChunkPos.z);

            // chunk loading
            ServerChunkManager serverChunkManager = ((ServerWorld) world).getChunkManager();
            if (!isEntityTickingChunk(serverChunkManager.getWorldChunk(nextChunkPos.x, nextChunkPos.z))) {
                int highestMotionBlockingY = MIN_Y;
                try {
                    NbtCompound nbtCompound = serverChunkManager.threadedAnvilChunkStorage.getNbt(nextChunkPos);
                    highestMotionBlockingY = getHighestMotionBlockingY(nbtCompound);
                } catch (IOException e) {
                    System.out.println("getNbt IOException");
                    e.printStackTrace();
                }

                // System.out.println(this.realPos.y + " " + highestMotionBlockingY + " " + nextPos.y);

                // skip chunk loading
                if (this.realPos.y > highestMotionBlockingY &&
                        nextPos.y > highestMotionBlockingY &&
                        nextPos.y + nextVelocity.y > highestMotionBlockingY) {
                    // stay put
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, currChunkPos, 2, currChunkPos);
                    this.setVelocity(Vec3d.ZERO);
                    this.updatePosition(currPos);
                    this.sync = false;
                } else {
                    // move
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, realChunkPos, 2, realChunkPos);
                    this.setVelocity(this.realVelocity);
                    this.updatePosition(this.realPos);
                    this.sync = true;
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, nextChunkPos, 2, nextChunkPos);
                }
            } else {
                if (!this.sync) {
                    // move
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, realChunkPos, 2, realChunkPos);
                    this.setVelocity(this.realVelocity);
                    this.updatePosition(this.realPos);
                    this.sync = true;
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, nextChunkPos, 2, nextChunkPos);
                } else {
                    // nothing happens
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, currChunkPos, 2, currChunkPos);
                }
            }

            // update real pos and velocity
            this.realPos = nextPos;
            this.realVelocity = nextVelocity;
        }
    }

    // helper function
    private void updatePosition(Vec3d pos) {
        super.updatePosition(pos.x, pos.y, pos.z);
    }

}
