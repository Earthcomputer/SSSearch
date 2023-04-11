package net.earthcomputer.sssearch;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.codec.digest.DigestUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SSSearchData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path SEARCH_DATA_DIR = FabricLoader.getInstance().getConfigDir().resolve("sssearch");

    @Nullable
    private static SSSearchData current;

    private final String saveName;
    private final Map<GlobalPos, Item> posToItem = new HashMap<>();
    private final Map<Item, List<GlobalPos>> itemToPos = new HashMap<>();

    private SSSearchData(String saveName) {
        this.saveName = saveName;
    }

    public void clear() {
        posToItem.clear();
        itemToPos.clear();
    }

    public void addItem(GlobalPos pos, Item item) {
        Item oldItem = posToItem.put(pos, item);
        if (oldItem != null) {
            var itemToPos = this.itemToPos.get(oldItem);
            if (itemToPos != null) {
                itemToPos.remove(pos);
                if (itemToPos.isEmpty()) {
                    this.itemToPos.remove(oldItem);
                }
            }
        }
        itemToPos.computeIfAbsent(item, k -> new ArrayList<>()).add(pos);
    }

    public Collection<GlobalPos> getItemPositions(Item item) {
        List<GlobalPos> result = itemToPos.get(item);
        if (result != null) {
            return Collections.unmodifiableList(result);
        } else {
            return Collections.emptyList();
        }
    }

    public static SSSearchData forCurrentWorld() {
        Minecraft mc = Minecraft.getInstance();
        String saveName;
        if (mc.getCurrentServer() != null) {
            saveName = mc.getCurrentServer().ip;
            if (saveName.endsWith(":25565")) {
                saveName = saveName.substring(0, saveName.length() - 6);
            }
        } else if (mc.isConnectedToRealms()) {
            saveName = "Realms";
        } else {
            IntegratedServer singleplayerServer = mc.getSingleplayerServer();
            if (singleplayerServer != null) {
                saveName = singleplayerServer.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
            } else {
                saveName = "<unknown>";
            }
        }

        String sha1 = DigestUtils.sha1Hex(saveName);
        saveName = saveName.replaceAll("[^A-Za-z0-9_]", "_") + "_" + sha1;
        if (current != null) {
            if (!saveName.equals(current.saveName)) {
                current = null;
                SSSearch.setupMode = false;
                SSSearch.setPositionsToHighlight(Collections.emptyList());
            }
        }
        if (current == null) {
            try (BufferedReader reader = Files.newBufferedReader(SEARCH_DATA_DIR.resolve(saveName + ".json"))) {
                JsonObject json = GsonHelper.fromJson(GSON, reader, JsonObject.class);
                Objects.requireNonNull(json);
                current = fromJson(saveName, json);
            } catch (NoSuchFileException e) {
                current = new SSSearchData(saveName);
            } catch (Throwable e) {
                LOGGER.error("Failed to read SSSearchData", e);
                current = new SSSearchData(saveName);
            }
        }
        return current;
    }

    public void save() {
        try {
            Files.createDirectories(SEARCH_DATA_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(SEARCH_DATA_DIR.resolve(saveName + ".json"))) {
                GSON.toJson(toJson(), writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save SSSearchData", e);
        }
    }

    private JsonObject toJson() {
        var entriesByDimension = posToItem.entrySet().stream().collect(Collectors.groupingBy(entry -> entry.getKey().dimension()));
        JsonObject result = new JsonObject();
        for (var dimensionAndEntry : entriesByDimension.entrySet()) {
            JsonArray valuesInDimension = new JsonArray(dimensionAndEntry.getValue().size());
            for (var entry : dimensionAndEntry.getValue()) {
                JsonObject entryJson = new JsonObject();
                BlockPos pos = entry.getKey().pos();
                entryJson.addProperty("x", pos.getX());
                entryJson.addProperty("y", pos.getY());
                entryJson.addProperty("z", pos.getZ());
                entryJson.addProperty("item", Registry.ITEM.getKey(entry.getValue()).toString());
                valuesInDimension.add(entryJson);
            }
            result.add(dimensionAndEntry.getKey().location().toString(), valuesInDimension);
        }
        return result;
    }

    private static SSSearchData fromJson(String saveName, JsonObject json) {
        SSSearchData result = new SSSearchData(saveName);
        for (var dimensionAndEntry : json.entrySet()) {
            ResourceKey<Level> dimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(dimensionAndEntry.getKey()));
            for (JsonElement entry : GsonHelper.convertToJsonArray(dimensionAndEntry.getValue(), dimensionAndEntry.getKey())) {
                JsonObject entryJson = GsonHelper.convertToJsonObject(entry, "entry of " + dimensionAndEntry.getKey());
                int x = GsonHelper.getAsInt(entryJson, "x");
                int y = GsonHelper.getAsInt(entryJson, "y");
                int z = GsonHelper.getAsInt(entryJson, "z");
                Item item = GsonHelper.getAsItem(entryJson, "item");

                GlobalPos pos = GlobalPos.of(dimension, new BlockPos(x, y, z));
                result.posToItem.put(pos, item);
                result.itemToPos.computeIfAbsent(item, k -> new ArrayList<>()).add(pos);
            }
        }
        return result;
    }
}
