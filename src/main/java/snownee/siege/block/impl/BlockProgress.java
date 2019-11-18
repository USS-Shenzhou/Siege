package snownee.siege.block.impl;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.annotation.Nonnull;

import com.google.common.collect.Maps;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.INBTSerializable;
import snownee.siege.SiegeConfig;
import snownee.siege.block.BlockModule;
import snownee.siege.block.capability.DefaultBlockInfo;
import snownee.siege.block.capability.IBlockProgress;
import snownee.siege.block.network.SyncBlockInfoPacket;

public class BlockProgress implements IBlockProgress, INBTSerializable<CompoundNBT> {

    private final Map<BlockPos, BlockInfo> progressData = Maps.newConcurrentMap();
    private final Chunk chunk;

    public BlockProgress(Chunk chunk) {
        this.chunk = chunk;
    }

    @Override
    public CompoundNBT serializeNBT() {
        return new CompoundNBT();
    }

    @Override
    public void deserializeNBT(CompoundNBT nbt) {
        //System.out.println(nbt);
    }

    @Override
    public Optional<BlockInfo> getInfo(BlockPos pos) {
        return Optional.ofNullable(progressData.get(pos));
    }

    @Override
    @Nonnull
    public BlockInfo getOrCreateInfo(BlockPos pos) {
        if (outOfLimit() && !progressData.containsKey(pos)) {
            Iterator<Entry<BlockPos, BlockInfo>> itr = progressData.entrySet().iterator();
            while (itr.hasNext() && outOfLimit()) {
                Entry<BlockPos, BlockInfo> e = itr.next();
                if (chunk.getWorld().isRemote && e.getValue().breakerID < 0) {
                    BlockModule.sendBreakAnimation(e.getValue().breakerID, pos, -1);
                }
                itr.remove();
            }
        }
        return progressData.computeIfAbsent(pos, s -> outOfLimit() ? DefaultBlockInfo.INSTANCE : new BlockInfo());
    }

    @Override
    public boolean destroy(BlockPos pos, float f, boolean sync) {
        if (f == 0) {
            return false;
        }
        BlockInfo info = f > 0 ? getOrCreateInfo(pos) : getInfo(pos).orElse(null);
        if (info == null) {
            return false;
        }
        BlockState state = info.getBlockState(chunk, pos);
        World world = chunk.getWorld();
        if (!BlockModule.canDamage(state) || state.getBlockHardness(world, pos) < 0) {
            return false;
        }
        float hardness = state.getBlockHardness(world, pos);
        float progress = info.getProgress() + f / hardness;
        info.setProgress(progress, world);
        progress = info.getProgress();
        if (progress == 0) {
            if (sync)
                new SyncBlockInfoPacket(pos, info.lastMine, -1).send(world);
            return true;
        } else if (progress < 1) {
            if (sync)
                new SyncBlockInfoPacket(pos, info.lastMine, info.getProgress()).send(world);
        } else {
            world.destroyBlock(pos, world.rand.nextFloat() < SiegeConfig.blockDropsRate);
            if (sync)
                new SyncBlockInfoPacket(pos, info.lastMine, -1).send(world);
            emptyInfo(pos);
        }
        return false;
    }

    @Override
    public boolean recover(BlockPos pos, float f, boolean sync) {
        return destroy(pos, -f, sync);
    }

    @Override
    public void emptyInfo(BlockPos pos) {
        progressData.remove(pos);
    }

    @Override
    public Map<BlockPos, BlockInfo> getAllData() {
        return progressData;
    }

    public boolean outOfLimit() {
        return progressData.size() >= SiegeConfig.maxDamagedBlockPerChunk;
    }
}
