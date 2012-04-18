/*
 * The FML Forge Mod Loader suite. Copyright (C) 2012 cpw
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package cpw.mods.fml.server;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Logger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.BaseMod;
import net.minecraft.server.BiomeBase;
import net.minecraft.server.CommonRegistry;
import net.minecraft.server.EntityItem;
import net.minecraft.server.EntityHuman;
import net.minecraft.server.IChunkProvider;
import net.minecraft.server.ICommandListener;
import net.minecraft.server.IInventory;
import net.minecraft.server.ItemStack;
import net.minecraft.server.NetworkManager;
import net.minecraft.server.Packet1Login;
import net.minecraft.server.Packet250CustomPayload;
import net.minecraft.server.Packet3Chat;
import net.minecraft.server.BukkitRegistry;
import net.minecraft.server.World;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * Handles primary communication from hooked code into the system
 * 
 * The FML entry point is {@link #onPreLoad(MinecraftServer)} called from {@link MinecraftServer}
 * 
 * Obfuscated code should focus on this class and other members of the "server" (or "client") code
 * 
 * The actual mod loading is handled at arms length by {@link Loader}
 * 
 * It is expected that a similar class will exist for each target environment: Bukkit and Client side.
 * 
 * It should not be directly modified.
 * 
 * @author cpw
 *
 */
public class FMLBukkitHandler implements IFMLSidedHandler
{
    /**
     * The singleton
     */
    private static final FMLBukkitHandler INSTANCE = new FMLBukkitHandler();

    /**
     * A reference to the server itself
     */
    private MinecraftServer server;

    /**
     * A handy list of the default overworld biomes
     */
    private BiomeBase[] defaultOverworldBiomes;

    /**
     * Called to start the whole game off from {@link MinecraftServer#startServer}
     * @param minecraftServer
     */
    public void onPreLoad(MinecraftServer minecraftServer)
    {
        server = minecraftServer;
        FMLCommonHandler.instance().registerSidedDelegate(this);
        CommonRegistry.registerRegistry(new BukkitRegistry());
        Loader.instance().loadMods();
    }

    /**
     * Called a bit later on during server initialization to finish loading mods
     */
    public void onLoadComplete()
    {
        Loader.instance().initializeMods();
    }

    /**
     * Every tick just before world and other ticks occur
     */
    public void onPreTick()
    {
        FMLCommonHandler.instance().gameTickStart();
    }

    /**
     * Every tick just after world and other ticks occur
     */
    public void onPostTick()
    {
        FMLCommonHandler.instance().gameTickEnd();
    }

    /**
     * Get the server instance
     * @return
     */
    public MinecraftServer getServer()
    {
        return server;
    }

    /**
     * Get a handle to the server's logger instance
     */
    public Logger getMinecraftLogger()
    {
        return MinecraftServer.log;
    }

    /**
     * Called from ChunkProviderServer when a chunk needs to be populated
     * 
     * To avoid polluting the worldgen seed, we generate a new random from the world seed and
     * generate a seed from that
     * 
     * @param chunkProvider
     * @param chunkX
     * @param chunkZ
     * @param world
     * @param generator
     */
    public void onChunkPopulate(IChunkProvider chunkProvider, int chunkX, int chunkZ, World world, IChunkProvider generator)
    {
        Random fmlRandom = new Random(world.getSeed());
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        fmlRandom.setSeed((xSeed * chunkX + zSeed * chunkZ) ^ world.getSeed());

        for (ModContainer mod : Loader.getModList())
        {
            if (mod.generatesWorld())
            {
                mod.getWorldGenerator().generate(fmlRandom, chunkX, chunkZ, world, generator, chunkProvider);
            }
        }
    }

    /**
     * Called from the furnace to lookup fuel values
     * 
     * @param itemId
     * @param itemDamage
     * @return
     */
    public int fuelLookup(int itemId, int itemDamage)
    {
        int fv = 0;

        for (ModContainer mod : Loader.getModList())
        {
            fv = Math.max(fv, mod.lookupFuelValue(itemId, itemDamage));
        }

        return fv;
    }

    /**
     * Is the offered class and instance of BaseMod and therefore a ModLoader mod?
     */
    public boolean isModLoaderMod(Class<?> clazz)
    {
        return BaseMod.class.isAssignableFrom(clazz);
    }

    /**
     * Load the supplied mod class into a mod container
     */
    public ModContainer loadBaseModMod(Class<?> clazz, File canonicalFile)
    {
        @SuppressWarnings("unchecked")
        Class <? extends BaseMod > bmClazz = (Class <? extends BaseMod >) clazz;
        return new ModLoaderModContainer(bmClazz, canonicalFile);
    }

    /**
     * Called to notify that an item was picked up from the world
     * @param entityItem
     * @param entityPlayer
     */
    public void notifyItemPickup(EntityItem entityItem, EntityHuman entityPlayer)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsPickupNotification())
            {
                mod.getPickupNotifier().notifyPickup(entityItem, entityPlayer);
            }
        }
    }

    /**
     * Raise an exception
     * @param exception
     * @param message
     * @param stopGame
     */
    public void raiseException(Throwable exception, String message, boolean stopGame)
    {
        FMLCommonHandler.instance().getFMLLogger().throwing("FMLHandler", "raiseException", exception);
        throw new RuntimeException(exception);
    }

    /**
     * Attempt to dispense the item as an entity other than just as a the item itself
     * 
     * @param world
     * @param x
     * @param y
     * @param z
     * @param xVelocity
     * @param zVelocity
     * @param item
     * @return
     */
    public boolean tryDispensingEntity(World world, double x, double y, double z, byte xVelocity, byte zVelocity, ItemStack item)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsToDispense() && mod.getDispenseHandler().dispense(x, y, z, xVelocity, zVelocity, world, item))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * @return the instance
     */
    public static FMLBukkitHandler instance()
    {
        return INSTANCE;
    }

    /**
     * Build a list of default overworld biomes
     * @return
     */
    public BiomeBase[] getDefaultOverworldBiomes()
    {
        if (defaultOverworldBiomes == null)
        {
            ArrayList<BiomeBase> biomes = new ArrayList<BiomeBase>(20);

            for (int i = 0; i < 23; i++)
            {
                if ("Sky".equals(BiomeBase.biomes[i].y) || "Hell".equals(BiomeBase.biomes[i].y))
                {
                    continue;
                }

                biomes.add(BiomeBase.biomes[i]);
            }

            defaultOverworldBiomes = new BiomeBase[biomes.size()];
            biomes.toArray(defaultOverworldBiomes);
        }

        return defaultOverworldBiomes;
    }

    /**
     * Called when an item is crafted
     * @param player
     * @param craftedItem
     * @param craftingGrid
     */
    public void onItemCrafted(EntityHuman player, ItemStack craftedItem, IInventory craftingGrid)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsCraftingNotification())
            {
                mod.getCraftingHandler().onCrafting(player, craftedItem, craftingGrid);
            }
        }
    }

    /**
     * Called when an item is smelted
     * 
     * @param player
     * @param smeltedItem
     */
    public void onItemSmelted(EntityHuman player, ItemStack smeltedItem)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsCraftingNotification())
            {
                mod.getCraftingHandler().onSmelting(player, smeltedItem);
            }
        }
    }

    /**
     * Called when a chat packet is received
     * 
     * @param chat
     * @param player
     * @return true if you want the packet to stop processing and not echo to the rest of the world
     */
    public boolean handleChatPacket(Packet3Chat chat, EntityHuman player)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsNetworkPackets() && mod.getNetworkHandler().onChat(chat, player))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Called when a packet 250 packet is received from the player
     * @param packet
     * @param player
     */
    public void handlePacket250(Packet250CustomPayload packet, EntityHuman player)
    {
        if ("REGISTER".equals(packet.tag) || "UNREGISTER".equals(packet.tag))
        {
            handleClientRegistration(packet, player);
            return;
        }

        ModContainer mod = FMLCommonHandler.instance().getModForChannel(packet.tag);

        if (mod != null)
        {
            mod.getNetworkHandler().onPacket250Packet(packet, player);
        }
    }

    /**
     * Handle register requests for packet 250 channels
     * @param packet
     */
    private void handleClientRegistration(Packet250CustomPayload packet, EntityHuman player)
    {
        if (packet.data==null) {
            return;
        }
        try
        {
            for (String channel : new String(packet.data, "UTF8").split("\0"))
            {
                // Skip it if we don't know it
                if (FMLCommonHandler.instance().getModForChannel(channel) == null)
                {
                    continue;
                }

                if ("REGISTER".equals(packet.tag))
                {
                    FMLCommonHandler.instance().activateChannel(player, channel);
                }
                else
                {
                    FMLCommonHandler.instance().deactivateChannel(player, channel);
                }
            }
        }
        catch (UnsupportedEncodingException e)
        {
            getMinecraftLogger().warning("Received invalid registration packet");
        }
    }

    /**
     * Handle a login
     * @param loginPacket
     * @param networkManager
     */
    public void handleLogin(Packet1Login loginPacket, NetworkManager networkManager)
    {
        Packet250CustomPayload packet = new Packet250CustomPayload();
        packet.tag = "REGISTER";
        packet.data = FMLCommonHandler.instance().getPacketRegistry();
        packet.length = packet.data.length;
        if (packet.length>0) {
            networkManager.queue(packet);
        }
    }

    public void announceLogin(EntityHuman player) {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsPlayerTracking())
            {
                mod.getPlayerTracker().onPlayerLogin(player);
            }
        }
    }
    /**
     * Are we a server?
     */
    @Override
    public boolean isServer()
    {
        return true;
    }

    /**
     * Are we a client?
     */
    @Override
    public boolean isClient()
    {
        return false;
    }

    @Override
    public File getMinecraftRootDirectory()
    {
        try {
            return server.a(".").getCanonicalFile();
        } catch (IOException ioe) {
            return new File(".");
        }
    }

    /**
     * @param var2
     * @return
     */
    public boolean handleServerCommand(String command, String player, ICommandListener listener)
    {
        for (ModContainer mod : Loader.getModList()) {
            if (mod.wantsConsoleCommands() && mod.getConsoleHandler().handleCommand(command, player, listener)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param player
     */
    public void announceLogout(EntityHuman player)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsPlayerTracking())
            {
                mod.getPlayerTracker().onPlayerLogout(player);
            }
        }
    }

    /**
     * @param p_28168_1_
     */
    public void announceDimensionChange(EntityHuman player)
    {
        for (ModContainer mod : Loader.getModList())
        {
            if (mod.wantsPlayerTracking())
            {
                mod.getPlayerTracker().onPlayerChangedDimension(player);
            }
        }
    }
}
