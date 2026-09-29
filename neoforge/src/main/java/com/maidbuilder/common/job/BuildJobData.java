package com.maidbuilder.common.job;

import com.maidbuilder.MaidBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** All build jobs of a server, saved with the overworld's data storage. */
public final class BuildJobData extends SavedData {
    public static final String NAME = MaidBuilder.MOD_ID + "_jobs";
    public static final Factory<BuildJobData> FACTORY = new Factory<>(BuildJobData::new, BuildJobData::load, null);

    private final Map<UUID, BuildJob> jobs = new LinkedHashMap<>();

    public BuildJob get(UUID id) {
        return jobs.get(id);
    }

    public Collection<BuildJob> all() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    public void add(BuildJob job) {
        job.setDirtyListener(this::setDirty);
        jobs.put(job.id(), job);
        setDirty();
    }

    public BuildJob remove(UUID id) {
        BuildJob removed = jobs.remove(id);
        if (removed != null) setDirty();
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BuildJob job : jobs.values()) list.add(job.save());
        tag.put("Jobs", list);
        return tag;
    }

    private static BuildJobData load(CompoundTag tag, HolderLookup.Provider registries) {
        BuildJobData data = new BuildJobData();
        for (Tag t : tag.getList("Jobs", Tag.TAG_COMPOUND)) {
            try {
                BuildJob job = BuildJob.load((CompoundTag) t);
                job.setDirtyListener(data::setDirty);
                data.jobs.put(job.id(), job);
            } catch (RuntimeException e) {
                MaidBuilder.LOGGER.error("Dropping unreadable build job {}", t, e);
            }
        }
        return data;
    }
}
