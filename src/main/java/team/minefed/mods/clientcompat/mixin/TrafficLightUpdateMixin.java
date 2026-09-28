/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
package team.minefed.mods.clientcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import de.mrjulsen.mcdragonlib.block.SyncedBlockEntity;
import de.mrjulsen.trafficcraft.block.entity.TrafficLightBlockEntity;
import java.util.Collection;
import java.util.Iterator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DragonLib's notifyUpdate() calls setChanged and queues a block update plus the full
 * block-entity NBT for every tracking player. TrafficCraft also calls it when setPowered,
 * stopSchedule or enableOnlyColors leave their fields as they were, e.g. setPowered(false)
 * on every neighbour change of an unpowered traffic light. Only in that case, keep
 * setChanged (chunk saving, comparators) and skip the redundant packets. Clients already
 * hold the same values because every change of a field they read is still sent.
 * Selectors are the pinned TrafficCraft 1.20.4-1.1.3 / Fabric 1.20.4 intermediary names.
 */
@Pseudo
@Mixin(targets = "de.mrjulsen.trafficcraft.block.entity.TrafficLightBlockEntity", remap = false)
public abstract class TrafficLightUpdateMixin {
    @Shadow(remap = false) private boolean powered;
    @Shadow(remap = false) private boolean running;
    @Shadow(remap = false) private int ticker;
    @Shadow(remap = false) private long totalTicks;
    @Shadow(remap = false) @Final private Collection<?> enabledColors;

    @Inject(method = "setPowered(Z)V", at = @At("HEAD"), remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$rememberPowered(boolean value, CallbackInfo ci, @Share("unchanged") LocalBooleanRef unchanged) {
        unchanged.set(this.powered == value);
    }

    @Inject(method = "stopSchedule()V", at = @At("HEAD"), remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$rememberStopped(CallbackInfo ci, @Share("unchanged") LocalBooleanRef unchanged) {
        unchanged.set(!this.running && this.ticker == 0 && this.totalTicks == 0);
    }

    @Inject(method = "enableOnlyColors(Ljava/util/Collection;)V", at = @At("HEAD"), remap = false, require = 1, expect = 1, allow = 1)
    private void minefed$rememberColors(Collection<?> colors, CallbackInfo ci, @Share("colors") LocalRef<Object[]> before) {
        before.set(this.enabledColors.toArray());
    }

    @WrapOperation(method = {"setPowered(Z)V", "stopSchedule()V"}, remap = false, require = 2, expect = 2, allow = 2,
        at = @At(value = "INVOKE", target = "Lde/mrjulsen/trafficcraft/block/entity/TrafficLightBlockEntity;notifyUpdate()V", remap = false))
    private void minefed$notifyChangedState(TrafficLightBlockEntity entity, Operation<Void> original,
            @Share("unchanged") LocalBooleanRef unchanged) {
        if (unchanged.get()) {
            SyncedBlockEntity synced = entity;
            synced.method_5431();
        } else {
            original.call(entity);
        }
    }

    /** Compares the list after TrafficCraft's clear/addAll with the one before, element by element. */
    @WrapOperation(method = "enableOnlyColors(Ljava/util/Collection;)V", remap = false, require = 1, expect = 1, allow = 1,
        at = @At(value = "INVOKE", target = "Lde/mrjulsen/trafficcraft/block/entity/TrafficLightBlockEntity;notifyUpdate()V", remap = false))
    private void minefed$notifyChangedColors(TrafficLightBlockEntity entity, Operation<Void> original,
            @Share("colors") LocalRef<Object[]> before) {
        Object[] previous = before.get();
        boolean unchanged = previous.length == this.enabledColors.size();
        Iterator<?> current = this.enabledColors.iterator();
        for (int i = 0; unchanged && i < previous.length; i++) unchanged = current.next() == previous[i];
        if (unchanged) {
            SyncedBlockEntity synced = entity;
            synced.method_5431();
        } else {
            original.call(entity);
        }
    }
}
