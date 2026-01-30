package com.buuz135.simpleclaims.map;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.guild.GuildClaimBridge;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import ru.hytaleworld.guild.util.Guild;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class CustomImageBuilder {
    private final long index;
    private final World world;
    @Nonnull
    private final MapImage image;
    private final int sampleWidth;
    private final int sampleHeight;
    private final int blockStepX;
    private final int blockStepZ;
    @Nonnull
    private final short[] heightSamples;
    @Nonnull
    private final int[] tintSamples;
    @Nonnull
    private final int[] blockSamples;
    @Nonnull
    private final short[] neighborHeightSamples;
    @Nonnull
    private final short[] fluidDepthSamples;
    @Nonnull
    private final int[] environmentSamples;
    @Nonnull
    private final int[] fluidSamples;
    private final CustomImageBuilder.Color outColor = new CustomImageBuilder.Color();
    @Nullable
    private WorldChunk worldChunk;
    private FluidSection[] fluidSections;

    public CustomImageBuilder(long index, int imageWidth, int imageHeight, World world) {
        this.index = index;
        this.world = world;
        this.image = new MapImage(imageWidth, imageHeight, new int[imageWidth * imageHeight]);
        this.sampleWidth = Math.min(32, this.image.width);
        this.sampleHeight = Math.min(32, this.image.height);
        this.blockStepX = Math.max(1, 32 / this.image.width);
        this.blockStepZ = Math.max(1, 32 / this.image.height);
        this.heightSamples = new short[this.sampleWidth * this.sampleHeight];
        this.tintSamples = new int[this.sampleWidth * this.sampleHeight];
        this.blockSamples = new int[this.sampleWidth * this.sampleHeight];
        this.neighborHeightSamples = new short[(this.sampleWidth + 2) * (this.sampleHeight + 2)];
        this.fluidDepthSamples = new short[this.sampleWidth * this.sampleHeight];
        this.environmentSamples = new int[this.sampleWidth * this.sampleHeight];
        this.fluidSamples = new int[this.sampleWidth * this.sampleHeight];
    }

    public long getIndex() {
        return this.index;
    }

    @Nonnull
    public MapImage getImage() {
        return this.image;
    }

    @Nonnull
    private CompletableFuture<CustomImageBuilder> fetchChunk() {
        return this.world.getChunkStore().getChunkReferenceAsync(this.index).thenApplyAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                this.worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                ChunkColumn chunkColumn = (ChunkColumn)ref.getStore().getComponent(ref, ChunkColumn.getComponentType());
                this.fluidSections = new FluidSection[10];

                for(int y = 0; y < 10; ++y) {
                    Ref<ChunkStore> sectionRef = chunkColumn.getSection(y);
                    this.fluidSections[y] = (FluidSection)this.world.getChunkStore().getStore().getComponent(sectionRef, FluidSection.getComponentType());
                }

                return this;
            } else {
                return null;
            }
        }, this.world);
    }

    @Nonnull
    private CompletableFuture<CustomImageBuilder> sampleNeighborsSync() {
        CompletableFuture<Void> north = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX(), this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int z = (this.sampleHeight - 1) * this.blockStepZ;

                for(int ix = 0; ix < this.sampleWidth; ++ix) {
                    int x = ix * this.blockStepX;
                    this.neighborHeightSamples[1 + ix] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> south = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX(), this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int z = 0;
                int neighbourStartIndex = (this.sampleHeight + 1) * (this.sampleWidth + 2) + 1;

                for(int ix = 0; ix < this.sampleWidth; ++ix) {
                    int x = ix * this.blockStepX;
                    this.neighborHeightSamples[neighbourStartIndex + ix] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> west = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ())).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;

                for(int iz = 0; iz < this.sampleHeight; ++iz) {
                    int z = iz * this.blockStepZ;
                    this.neighborHeightSamples[(iz + 1) * (this.sampleWidth + 2)] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> east = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ())).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;

                for(int iz = 0; iz < this.sampleHeight; ++iz) {
                    int z = iz * this.blockStepZ;
                    this.neighborHeightSamples[(iz + 1) * (this.sampleWidth + 2) + this.sampleWidth + 1] = worldChunk.getHeight(x, z);
                }

            }
        }, this.world);
        CompletableFuture<Void> northeast = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;
                int z = (this.sampleHeight - 1) * this.blockStepZ;
                this.neighborHeightSamples[0] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> northwest = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ() - 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;
                int z = (this.sampleHeight - 1) * this.blockStepZ;
                this.neighborHeightSamples[this.sampleWidth + 1] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> southeast = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() + 1, this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = 0;
                int z = 0;
                this.neighborHeightSamples[(this.sampleHeight + 1) * (this.sampleWidth + 2) + this.sampleWidth + 1] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        CompletableFuture<Void> southwest = this.world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(this.worldChunk.getX() - 1, this.worldChunk.getZ() + 1)).thenAcceptAsync((ref) -> {
            if (ref != null && ref.isValid()) {
                WorldChunk worldChunk = (WorldChunk)ref.getStore().getComponent(ref, WorldChunk.getComponentType());
                int x = (this.sampleWidth - 1) * this.blockStepX;
                int z = 0;
                this.neighborHeightSamples[(this.sampleHeight + 1) * (this.sampleWidth + 2)] = worldChunk.getHeight(x, z);
            }
        }, this.world);
        return CompletableFuture.allOf(north, south, west, east, northeast, northwest, southeast, southwest).thenApply((v) -> this);
    }

    private CustomImageBuilder generateImageAsync() {
        for(int ix = 0; ix < this.sampleWidth; ++ix) {
            for(int iz = 0; iz < this.sampleHeight; ++iz) {
                int sampleIndex = iz * this.sampleWidth + ix;
                int x = ix * this.blockStepX;
                int z = iz * this.blockStepZ;
                short height = this.worldChunk.getHeight(x, z);
                int tint = this.worldChunk.getTint(x, z);
                this.heightSamples[sampleIndex] = height;
                this.tintSamples[sampleIndex] = tint;
                int blockId = this.worldChunk.getBlock(x, height, z);
                this.blockSamples[sampleIndex] = blockId;
                int fluidId = 0;
                int fluidTop = 320;
                Fluid fluid = null;
                int chunkYGround = ChunkUtil.chunkCoordinate(height);
                int chunkY = 9;

                label97:
                while(chunkY >= 0 && chunkY >= chunkYGround) {
                    FluidSection fluidSection = this.fluidSections[chunkY];
                    if (fluidSection != null && !fluidSection.isEmpty()) {
                        int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
                        int maxBlockY = ChunkUtil.maxBlock(chunkY);

                        for(int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
                            fluidId = fluidSection.getFluidId(x, blockY, z);
                            if (fluidId != 0) {
                                fluid = (Fluid)Fluid.getAssetMap().getAsset(fluidId);
                                fluidTop = blockY;
                                break label97;
                            }
                        }

                        --chunkY;
                    } else {
                        --chunkY;
                    }
                }

                int fluidBottom;
                label119:
                for(fluidBottom = height; chunkY >= 0 && chunkY >= chunkYGround; --chunkY) {
                    FluidSection fluidSection = this.fluidSections[chunkY];
                    if (fluidSection == null || fluidSection.isEmpty()) {
                        fluidBottom = Math.min(ChunkUtil.maxBlock(chunkY) + 1, fluidTop);
                        break;
                    }

                    int minBlockY = Math.max(ChunkUtil.minBlock(chunkY), height);
                    int maxBlockY = Math.min(ChunkUtil.maxBlock(chunkY), fluidTop - 1);

                    for(int blockY = maxBlockY; blockY >= minBlockY; --blockY) {
                        int nextFluidId = fluidSection.getFluidId(x, blockY, z);
                        if (nextFluidId != fluidId) {
                            Fluid nextFluid = (Fluid)Fluid.getAssetMap().getAsset(nextFluidId);
                            if (!Objects.equals(fluid.getParticleColor(), nextFluid.getParticleColor())) {
                                fluidBottom = blockY + 1;
                                break label119;
                            }
                        }
                    }
                }

                short fluidDepth = fluidId != 0 ? (short)(fluidTop - fluidBottom + 1) : 0;
                int environmentId = this.worldChunk.getBlockChunk().getEnvironment(x, fluidTop, z);
                this.fluidDepthSamples[sampleIndex] = fluidDepth;
                this.environmentSamples[sampleIndex] = environmentId;
                this.fluidSamples[sampleIndex] = fluidId;
            }
        }

        float imageToSampleRatioWidth = (float)this.sampleWidth / (float)this.image.width;
        float imageToSampleRatioHeight = (float)this.sampleHeight / (float)this.image.height;
        int blockPixelWidth = Math.max(1, this.image.width / this.sampleWidth);
        int blockPixelHeight = Math.max(1, this.image.height / this.sampleHeight);

        for(int iz = 0; iz < this.sampleHeight; ++iz) {
            System.arraycopy(this.heightSamples, iz * this.sampleWidth, this.neighborHeightSamples, (iz + 1) * (this.sampleWidth + 2) + 1, this.sampleWidth);
        }

        int chunkX = ChunkUtil.xOfChunkIndex(this.index);
        int chunkZ = ChunkUtil.zOfChunkIndex(this.index);
        int minBlockX = ChunkUtil.minBlock(chunkX);
        int minBlockZ = ChunkUtil.minBlock(chunkZ);

        // === CUSTOM CODE: GUILD SUPPORT ===
        // Получаем информацию о владельце чанка
        var claimedChunk = ClaimManager.getInstance().getChunk(
                this.worldChunk.getWorld().getName(),
                this.worldChunk.getX(),
                this.worldChunk.getZ()
        );

        Integer chunkColor = null;
        String chunkOwnerName = null;

        if (claimedChunk != null) {
            switch (claimedChunk.getOwnerType()) {
                case PARTY -> {
                    PartyInfo partyInfo = ClaimManager.getInstance().getPartyById(claimedChunk.getOwnerId());
                    if (partyInfo != null) {
                        chunkColor = partyInfo.getColor();
                        chunkOwnerName = partyInfo.getName();
                    }
                }

                case GUILD -> {
                    Guild guild = ClaimManager.getInstance().getGuildById(claimedChunk.getOwnerId());
                    if (guild != null) {
                        // Парсим цвет из HEX строки
                        String colorStr = GuildClaimBridge.getChatColor(guild);
                        if (colorStr != null && colorStr.startsWith("#")) {
                            try {
                                chunkColor = Integer.parseInt(colorStr.substring(1), 16);
                            } catch (NumberFormatException e) {
                                chunkColor = 0xFFAA00; // Оранжевый по умолчанию
                            }
                        } else {
                            chunkColor = 0xFFAA00;
                        }
                        chunkOwnerName = GuildClaimBridge.getGuildName(guild);
                    }
                }

                case PERSONAL -> {
                    chunkColor = 0x00BFFF; // DeepSkyBlue
                    chunkOwnerName = "Personal";
                }
            }
        }

        // Получаем соседние чанки для отрисовки границ
        var nearbyChunks = new ChunkInfo[]{
                ClaimManager.getInstance().getChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX(), this.worldChunk.getZ() + 1), //NORTH
                ClaimManager.getInstance().getChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX(), this.worldChunk.getZ() - 1), //SOUTH
                ClaimManager.getInstance().getChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX() + 1, this.worldChunk.getZ()), //EAST
                ClaimManager.getInstance().getChunk(this.worldChunk.getWorld().getName(), this.worldChunk.getX() - 1, this.worldChunk.getZ()), //WEST
        };
        // === END CUSTOM CODE ===

        for(int ix = 0; ix < this.image.width; ++ix) {
            for(int iz = 0; iz < this.image.height; ++iz) {
                int sampleX = Math.min((int)((float)ix * imageToSampleRatioWidth), this.sampleWidth - 1);
                int sampleZ = Math.min((int)((float)iz * imageToSampleRatioHeight), this.sampleHeight - 1);
                int sampleIndex = sampleZ * this.sampleWidth + sampleX;
                int blockPixelX = ix % blockPixelWidth;
                int blockPixelZ = iz % blockPixelHeight;
                short height = this.heightSamples[sampleIndex];
                int tint = this.tintSamples[sampleIndex];
                int blockId = this.blockSamples[sampleIndex];
                getBlockColor(blockId, tint, this.outColor);

                short north = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX + 1];
                short south = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX + 1];
                short west = this.neighborHeightSamples[(sampleZ + 1) * (this.sampleWidth + 2) + sampleX];
                short east = this.neighborHeightSamples[(sampleZ + 1) * (this.sampleWidth + 2) + sampleX + 2];
                short northWest = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX];
                short northEast = this.neighborHeightSamples[sampleZ * (this.sampleWidth + 2) + sampleX + 2];
                short southWest = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX];
                short southEast = this.neighborHeightSamples[(sampleZ + 2) * (this.sampleWidth + 2) + sampleX + 2];
                float shade = shadeFromHeights(blockPixelX, blockPixelZ, blockPixelWidth, blockPixelHeight, height, north, south, west, east, northWest, northEast, southWest, southEast);
                this.outColor.multiply(shade);

                if (height < 320) {
                    int fluidId = this.fluidSamples[sampleIndex];
                    if (fluidId != 0) {
                        short fluidDepth = this.fluidDepthSamples[sampleIndex];
                        int environmentId = this.environmentSamples[sampleIndex];
                        getFluidColor(fluidId, environmentId, fluidDepth, this.outColor);
                    }
                }

                // === CUSTOM CODE: ПРИМЕНЕНИЕ ЦВЕТА КЛАЙМА ===
                if (chunkColor != null) {
                    var isBorder = false;
                    var borderSize = 2;

                    // Проверяем, является ли текущий пиксель границей
                    if ((ix <= borderSize && (nearbyChunks[3] == null || !isSameOwner(claimedChunk, nearbyChunks[3]))) // WEST
                            || (ix >= this.image.width - borderSize - 1 && (nearbyChunks[2] == null || !isSameOwner(claimedChunk, nearbyChunks[2]))) // EAST
                            || (iz <= borderSize && (nearbyChunks[1] == null || !isSameOwner(claimedChunk, nearbyChunks[1]))) // SOUTH (was NORTH)
                            || (iz >= this.image.height - borderSize - 1 && (nearbyChunks[0] == null || !isSameOwner(claimedChunk, nearbyChunks[0])))) { // NORTH (was SOUTH)
                        isBorder = true;
                    }

                    getForceBlockColor(blockId, chunkColor, this.outColor, isBorder);
                }
                // === END CUSTOM CODE ===

                this.populateImageData(iz * this.image.width + ix, sampleX, sampleZ, minBlockX, minBlockZ);
            }
        }

        // === CUSTOM CODE: ОТРИСОВКА НАЗВАНИЯ ВЛАДЕЛЬЦА ===
        if (chunkColor != null && chunkOwnerName != null && Main.CONFIG.get().isRenderClaimNamesOnWorldMap()) {
            String name = chunkOwnerName.toUpperCase();
            // Тень (чёрная обводка)
            drawText(this.image, 1, 1, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 1, 2, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 1, 3, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 2, 1, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 2, 3, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 3, 1, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 3, 2, name, new Color(0, 0, 0, 255).pack());
            drawText(this.image, 3, 3, name, new Color(0, 0, 0, 255).pack());
            // Основной текст (белый)
            drawText(this.image, 2, 2, name, new Color(255, 255, 255, 255).pack());
        }
        // === END CUSTOM CODE ===

        return this;
    }

    private static float shadeFromHeights(int blockPixelX, int blockPixelZ, int blockPixelWidth, int blockPixelHeight, short height, short north, short south, short west, short east, short northWest, short northEast, short southWest, short southEast) {
        float u = ((float)blockPixelX + 0.5F) / (float)blockPixelWidth;
        float v = ((float)blockPixelZ + 0.5F) / (float)blockPixelHeight;
        float ud = (u + v) / 2.0F;
        float vd = (1.0F - u + v) / 2.0F;
        float dhdx1 = (float)(height - west) * (1.0F - u) + (float)(east - height) * u;
        float dhdz1 = (float)(height - north) * (1.0F - v) + (float)(south - height) * v;
        float dhdx2 = (float)(height - northWest) * (1.0F - ud) + (float)(southEast - height) * ud;
        float dhdz2 = (float)(height - northEast) * (1.0F - vd) + (float)(southWest - height) * vd;
        float dhdx = dhdx1 * 2.0F + dhdx2;
        float dhdz = dhdz1 * 2.0F + dhdz2;
        float dy = 3.0F;
        float invS = 1.0F / (float)Math.sqrt((double)(dhdx * dhdx + dy * dy + dhdz * dhdz));
        float nx = dhdx * invS;
        float ny = dy * invS;
        float nz = dhdz * invS;
        float lx = -0.2F;
        float ly = 0.8F;
        float lz = 0.5F;
        float invL = 1.0F / (float)Math.sqrt((double)(lx * lx + ly * ly + lz * lz));
        lx *= invL;
        ly *= invL;
        lz *= invL;
        float lambert = Math.max(0.0F, nx * lx + ny * ly + nz * lz);
        float ambient = 0.4F;
        float diffuse = 0.6F;
        return ambient + diffuse * lambert;
    }

    private static void getBlockColor(int blockId, int biomeTintColor, @Nonnull CustomImageBuilder.Color outColor) {
        BlockType block = (BlockType)BlockType.getAssetMap().getAsset(blockId);
        int biomeTintR = biomeTintColor >> 16 & 255;
        int biomeTintG = biomeTintColor >> 8 & 255;
        int biomeTintB = biomeTintColor >> 0 & 255;
        com.hypixel.hytale.protocol.Color[] tintUp = block.getTintUp();
        boolean hasTint = tintUp != null && tintUp.length > 0;
        int selfTintR = hasTint ? tintUp[0].red & 255 : 255;
        int selfTintG = hasTint ? tintUp[0].green & 255 : 255;
        int selfTintB = hasTint ? tintUp[0].blue & 255 : 255;
        float biomeTintMultiplier = (float)block.getBiomeTintUp() / 100.0F;
        int tintColorR = (int)((float)selfTintR + (float)(biomeTintR - selfTintR) * biomeTintMultiplier);
        int tintColorG = (int)((float)selfTintG + (float)(biomeTintG - selfTintG) * biomeTintMultiplier);
        int tintColorB = (int)((float)selfTintB + (float)(biomeTintB - selfTintB) * biomeTintMultiplier);
        com.hypixel.hytale.protocol.Color particleColor = block.getParticleColor();
        if (particleColor != null && biomeTintMultiplier < 1.0F) {
            tintColorR = tintColorR * (particleColor.red & 255) / 255;
            tintColorG = tintColorG * (particleColor.green & 255) / 255;
            tintColorB = tintColorB * (particleColor.blue & 255) / 255;
        }

        outColor.r = tintColorR & 255;
        outColor.g = tintColorG & 255;
        outColor.b = tintColorB & 255;
        outColor.a = 255;
    }

    private static void getForceBlockColor(int blockId, int partyColor, @Nonnull CustomImageBuilder.Color outColor, boolean isBorder) {
        int biomeTintR = partyColor >> 16 & 255;
        int biomeTintG = partyColor >> 8 & 255;
        int biomeTintB = partyColor >> 0 & 255;

        float overlayAlpha = isBorder ? 0.75f : 0.4f;

        outColor.r = (int)(outColor.r * (1 - overlayAlpha) + biomeTintR * overlayAlpha);
        outColor.g = (int)(outColor.g * (1 - overlayAlpha) + biomeTintG * overlayAlpha);
        outColor.b = (int)(outColor.b * (1 - overlayAlpha) + biomeTintB * overlayAlpha);
        outColor.a = 255;
    }

    private static void getFluidColor(int fluidId, int environmentId, int fluidDepth, @Nonnull CustomImageBuilder.Color outColor) {
        int tintColorR = 255;
        int tintColorG = 255;
        int tintColorB = 255;
        Environment environment = (Environment)Environment.getAssetMap().getAsset(environmentId);
        com.hypixel.hytale.protocol.Color waterTint = environment.getWaterTint();
        if (waterTint != null) {
            tintColorR = tintColorR * (waterTint.red & 255) / 255;
            tintColorG = tintColorG * (waterTint.green & 255) / 255;
            tintColorB = tintColorB * (waterTint.blue & 255) / 255;
        }

        Fluid fluid = (Fluid)Fluid.getAssetMap().getAsset(fluidId);
        com.hypixel.hytale.protocol.Color partcileColor = fluid.getParticleColor();
        if (partcileColor != null) {
            tintColorR = tintColorR * (partcileColor.red & 255) / 255;
            tintColorG = tintColorG * (partcileColor.green & 255) / 255;
            tintColorB = tintColorB * (partcileColor.blue & 255) / 255;
        }

        float depthMultiplier = Math.min(1.0F, 1.0F / (float)fluidDepth);
        outColor.r = (int)((float)tintColorR + (float)((outColor.r & 255) - tintColorR) * depthMultiplier) & 255;
        outColor.g = (int)((float)tintColorG + (float)((outColor.g & 255) - tintColorG) * depthMultiplier) & 255;
        outColor.b = (int)((float)tintColorB + (float)((outColor.b & 255) - tintColorB) * depthMultiplier) & 255;
    }

    private void populateImageData(int pixelIndex, int sampleX, int sampleZ, int minBlockX, int minBlockZ) {
        this.image.data[pixelIndex] = this.outColor.pack();
    }

    private void drawText(MapImage image, int x, int y, String text, int color) {
        for(int i = 0; i < text.length(); i++) {
            drawChar(image, x + i * 4 + 4, y + 4, text.charAt(i), color);
        }
    }

    private void drawChar(MapImage image, int x, int y, char c, int color) {
        if (c == ' ') return;
        byte[] glyph = getGlyph(c);
        for(int gy = 0; gy < 5; gy++) {
            for(int gx = 0; gx < 3; gx++) {
                if (((glyph[gy] >> (2 - gx)) & 1) == 1) {
                    int px = x + gx;
                    int py = y + gy;
                    if (px >= 0 && px < image.width && py >= 0 && py < image.height) {
                        image.data[py * image.width + px] = color;
                    }
                }
            }
        }
    }

    private byte[] getGlyph(char c) {
        switch(Character.toUpperCase(c)) {
            case 'A': return new byte[]{0b010, 0b101, 0b111, 0b101, 0b101};
            case 'B': return new byte[]{0b110, 0b101, 0b110, 0b101, 0b110};
            case 'C': return new byte[]{0b011, 0b100, 0b100, 0b100, 0b011};
            case 'D': return new byte[]{0b110, 0b101, 0b101, 0b101, 0b110};
            case 'E': return new byte[]{0b111, 0b100, 0b110, 0b100, 0b111};
            case 'F': return new byte[]{0b111, 0b100, 0b110, 0b100, 0b100};
            case 'G': return new byte[]{0b011, 0b100, 0b101, 0b101, 0b011};
            case 'H': return new byte[]{0b101, 0b101, 0b111, 0b101, 0b101};
            case 'I': return new byte[]{0b111, 0b010, 0b010, 0b010, 0b111};
            case 'J': return new byte[]{0b001, 0b001, 0b001, 0b101, 0b010};
            case 'K': return new byte[]{0b101, 0b101, 0b110, 0b101, 0b101};
            case 'L': return new byte[]{0b100, 0b100, 0b100, 0b100, 0b111};
            case 'M': return new byte[]{0b101, 0b111, 0b101, 0b101, 0b101};
            case 'N': return new byte[]{0b101, 0b111, 0b111, 0b101, 0b101};
            case 'O': return new byte[]{0b010, 0b101, 0b101, 0b101, 0b010};
            case 'P': return new byte[]{0b110, 0b101, 0b110, 0b100, 0b100};
            case 'Q': return new byte[]{0b010, 0b101, 0b101, 0b011, 0b001};
            case 'R': return new byte[]{0b110, 0b101, 0b110, 0b101, 0b101};
            case 'S': return new byte[]{0b011, 0b100, 0b010, 0b001, 0b110};
            case 'T': return new byte[]{0b111, 0b010, 0b010, 0b010, 0b010};
            case 'U': return new byte[]{0b101, 0b101, 0b101, 0b101, 0b111};
            case 'V': return new byte[]{0b101, 0b101, 0b101, 0b101, 0b010};
            case 'W': return new byte[]{0b101, 0b101, 0b101, 0b111, 0b101};
            case 'X': return new byte[]{0b101, 0b101, 0b010, 0b101, 0b101};
            case 'Y': return new byte[]{0b101, 0b101, 0b010, 0b010, 0b010};
            case 'Z': return new byte[]{0b111, 0b001, 0b010, 0b100, 0b111};
            case ' ': return new byte[]{0b000, 0b000, 0b000, 0b000, 0b000};
            case '0': return new byte[]{0b111, 0b101, 0b101, 0b101, 0b111};
            case '1': return new byte[]{0b010, 0b110, 0b010, 0b010, 0b111};
            case '2': return new byte[]{0b111, 0b001, 0b111, 0b100, 0b111};
            case '3': return new byte[]{0b111, 0b001, 0b111, 0b001, 0b111};
            case '4': return new byte[]{0b101, 0b101, 0b111, 0b001, 0b001};
            case '5': return new byte[]{0b111, 0b100, 0b111, 0b001, 0b111};
            case '6': return new byte[]{0b111, 0b100, 0b111, 0b101, 0b111};
            case '7': return new byte[]{0b111, 0b001, 0b001, 0b001, 0b001};
            case '8': return new byte[]{0b111, 0b101, 0b111, 0b101, 0b111};
            case '9': return new byte[]{0b111, 0b101, 0b111, 0b001, 0b001};
            default: return new byte[]{0b000, 0b000, 0b000, 0b000, 0b000};
        }
    }

    @Nonnull
    public static CompletableFuture<CustomImageBuilder> build(long index, int imageWidth, int imageHeight, World world) {
        return CompletableFuture.completedFuture(new CustomImageBuilder(index, imageWidth, imageHeight, world))
                .thenCompose(CustomImageBuilder::fetchChunk)
                .thenCompose((builder) -> builder != null ? builder.sampleNeighborsSync() : CompletableFuture.completedFuture(null))
                .thenApplyAsync((builder) -> builder != null ? builder.generateImageAsync() : null);
    }

    /**
     * Вспомогательный метод: проверка, принадлежат ли два чанка одному владельцу
     */
    private static boolean isSameOwner(ChunkInfo chunk1, ChunkInfo chunk2) {
        if (chunk1 == null || chunk2 == null) {
            return false;
        }

        // Сравниваем тип владельца и ID владельца
        return chunk1.getOwnerType() == chunk2.getOwnerType() &&
                chunk1.getOwnerId().equals(chunk2.getOwnerId());
    }

    private static class Color {
        public int r;
        public int g;
        public int b;
        public int a;

        public Color(int r, int g, int b, int a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        public Color() {
        }

        public int pack() {
            return (this.r & 255) << 24 | (this.g & 255) << 16 | (this.b & 255) << 8 | this.a & 255;
        }

        public void multiply(float value) {
            this.r = Math.min(255, Math.max(0, (int)((float)this.r * value)));
            this.g = Math.min(255, Math.max(0, (int)((float)this.g * value)));
            this.b = Math.min(255, Math.max(0, (int)((float)this.b * value)));
        }
    }
}