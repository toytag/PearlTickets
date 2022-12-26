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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

// Mixin
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Java
import java.util.Comparator;
import java.util.concurrent.ExecutionException;


@Mixin(EnderPearlEntity.class)
public abstract class EnderPearlEntityMixin extends ThrownItemEntity {
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
        int highestY = Integer.MIN_VALUE;
        if (nbtCompound != null) {
            // vanilla 1.18+
            for (long element : nbtCompound.getCompound("Heightmaps").getLongArray("MOTION_BLOCKING")) {
                // 64 bits in long, 7 y-values * 9-bit-each = 63 bits, and 1 bit vacant.
                for (int i = 0; i < 7; i++) {
                    int y = (int)(element & 0b111111111) - 1;
                    if (y > highestY) highestY = y;
                    element = element >> 9;
                }
            }
        }
        return highestY;
    }

    @Inject(method = "tick", at = @At(value = "HEAD"))
    private void skippyChunkLoading(CallbackInfo ci) {
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

//            System.out.println("curr: " + currPos + currVelocity);
//            System.out.println("real: " + this.realPos + this.realVelocity);
//            System.out.println("next: " + nextPos + nextVelocity);

            // chunkPos to temporarily store pearl and next chunkPos to check chunk loading
            ChunkPos currChunkPos = new ChunkPos(new BlockPos(currPos));
            ChunkPos nextChunkPos = new ChunkPos(new BlockPos(nextPos));

//            System.out.printf("currChunkPos: (%d, %d)    realChunkPos: (%d, %d)    nextChunkPos: (%d, %d)\n",
//                 currChunkPos.x, currChunkPos.z, realChunkPos.x, realChunkPos.z, nextChunkPos.x, nextChunkPos.z);

            // chunk loading
            ServerChunkManager serverChunkManager = ((ServerWorld) world).getChunkManager();
            if (!this.sync || !isEntityTickingChunk(serverChunkManager.getWorldChunk(nextChunkPos.x, nextChunkPos.z))) {
                NbtCompound nbtCompound1;
                NbtCompound nbtCompound2;
                try {
                    nbtCompound1 = serverChunkManager.threadedAnvilChunkStorage.getNbt(currChunkPos).get().orElse(null);
                    nbtCompound2 = serverChunkManager.threadedAnvilChunkStorage.getNbt(nextChunkPos).get().orElse(null);
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("NbtCompound exception");
                }

                int highestMotionBlockingY = Integer.max(getHighestMotionBlockingY(nbtCompound1), getHighestMotionBlockingY(nbtCompound2));

//                System.out.println(this.realPos.y + " " + highestMotionBlockingY + " " + nextPos.y);

                // compatible with none-zero minimum y value dimension
                DimensionType worldDimension = world.getDimension();
                highestMotionBlockingY += worldDimension.minY();

                // skip chunk loading
                if (this.realPos.y > highestMotionBlockingY
                        && nextPos.y > highestMotionBlockingY
                        && nextPos.y + nextVelocity.y > highestMotionBlockingY) {
                    // stay put
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, currChunkPos, 2, currChunkPos);
                    this.setVelocity(Vec3d.ZERO);
                    this.setPosition(currPos);
                    this.sync = false;
                } else {
                    // move
                    serverChunkManager.addTicket(ENDER_PEARL_TICKET, nextChunkPos, 2, nextChunkPos);
                    this.setVelocity(this.realVelocity);
                    this.setPosition(this.realPos);
                    this.sync = true;
                }
            }

            // update real pos and velocity
            this.realPos = nextPos;
            this.realVelocity = nextVelocity;
        }
    }

}
