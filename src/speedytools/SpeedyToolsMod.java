package speedytools;

import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChunkCoordinates;
import net.minecraftforge.common.MinecraftForge;
import speedytools.client.ClientTickHandler;
import speedytools.client.ConfusedMovementInput;
import speedytools.client.KeyBindingInterceptor;
import speedytools.clientserversynch.PacketHandler;
import speedytools.items.*;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.registry.LanguageRegistry;
import net.minecraft.item.Item;

import java.util.List;


@Mod(modid="SpeedyToolsMod", name="Speedy Tools Mod", version="0.0.1")
@NetworkMod(clientSideRequired=true, serverSideRequired=true, channels={"SpeedyTools"}, packetHandler = PacketHandler.class)
public class SpeedyToolsMod {

  // The instance of your mod that Forge uses.
  @Mod.Instance("SpeedyToolsMod")
  public static speedytools.SpeedyToolsMod instance;

  // custom items
  private final static int STARTITEM = 5000;
  public final static Item itemSpeedyStripStrong = new ItemSpeedyStripStrong(STARTITEM);
  public final static Item itemSpeedyStripWeak = new ItemSpeedyStripWeak(STARTITEM+1);

  // custom blocks
  private final static int STARTBLOCK = 500;

  public static KeyBindingInterceptor attackButtonInterceptor;
  public static KeyBindingInterceptor useItemButtonInterceptor;

//  public static ConfusedMovementInput confusedMovementInput;

  // custom itemrenderers

  // Says where the client and server 'proxy' code is loaded.
  @SidedProxy(clientSide="speedytools.client.ClientProxy", serverSide="speedytools.CommonProxy")

  public static CommonProxy proxy;

  @EventHandler
  public void preInit(FMLPreInitializationEvent event) {
    // Stub Method
  }

  @EventHandler
  public void load(FMLInitializationEvent event) {
    addItemsToRegistries();
    addBlocksToRegistries();
    MinecraftForge.EVENT_BUS.register(new ItemEventHandler());
  }

  @EventHandler
  public void postInit(FMLPostInitializationEvent event) {
    attackButtonInterceptor = new KeyBindingInterceptor(Minecraft.getMinecraft().gameSettings.keyBindAttack);
    Minecraft.getMinecraft().gameSettings.keyBindAttack = attackButtonInterceptor;
    attackButtonInterceptor.setInterceptionActive(false);

    useItemButtonInterceptor = new KeyBindingInterceptor(Minecraft.getMinecraft().gameSettings.keyBindUseItem);
    Minecraft.getMinecraft().gameSettings.keyBindUseItem = useItemButtonInterceptor;
    useItemButtonInterceptor.setInterceptionActive(false);

    TickRegistry.registerTickHandler(new ClientTickHandler(), Side.CLIENT);
  }

  private void addItemsToRegistries() {
    // for all items:
    // LanguageRegistry for registering the name of the item
    // MinecraftForgeClient.registerItemRenderer for custom item renderers

    LanguageRegistry.addName(itemSpeedyStripWeak, "Wand of Benign Conjuration");
    LanguageRegistry.addName(itemSpeedyStripStrong, "Wand of Destructive Conjuration");
  }

  private void addBlocksToRegistries() {
    // for all blocks:
    // GameRegistry for associating an item with a block
    // LanguageRegistry for registering the name of the block
    // RenderingRegistry for custom block renderers
  }

}
