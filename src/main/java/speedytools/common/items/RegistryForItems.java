package speedytools.common.items;

import net.minecraftforge.fml.common.registry.GameRegistry;
import speedytools.common.SpeedyToolsOptions;

/**
 * creates and contains the instances of all of this mod's custom Items
 * the instances are created manually in order to control the creation time and order
 */
public class RegistryForItems
{
  // custom items
  public static ItemSpeedyWandStrong itemSpeedyWandStrong;
  public static ItemSpeedyWandWeak itemSpeedyWandWeak;
  public static ItemSpeedySceptre itemSpeedySceptre;
  public static ItemSpeedyOrb itemSpeedyOrb;
  public static ItemSpeedyBoundary itemSpeedyBoundary;
  public static ItemComplexCopy itemComplexCopy;
  public static ItemComplexMove itemComplexMove;
  public static ItemComplexDelete itemComplexDelete;
  public static ItemSpeedyTester itemSpeedyTester;

  public static void initialise()
  {
    itemSpeedyWandStrong = new ItemSpeedyWandStrong();
    itemSpeedyWandWeak = new ItemSpeedyWandWeak();
    itemSpeedySceptre = new ItemSpeedySceptre();
    itemSpeedyOrb = new ItemSpeedyOrb();
    itemSpeedyBoundary = new ItemSpeedyBoundary();
    itemComplexCopy = new ItemComplexCopy();
    itemComplexMove = new ItemComplexMove();
    itemComplexDelete = new ItemComplexDelete();

    GameRegistry.registerItem(itemSpeedyWandStrong, itemSpeedyWandStrong.NAME);
    GameRegistry.registerItem(itemSpeedyWandWeak, itemSpeedyWandWeak.NAME);
    GameRegistry.registerItem(itemSpeedySceptre, itemSpeedySceptre.NAME);
    GameRegistry.registerItem(itemSpeedyOrb, itemSpeedyOrb.NAME);
    GameRegistry.registerItem(itemSpeedyBoundary, itemSpeedyBoundary.NAME);
    GameRegistry.registerItem(itemComplexCopy, itemComplexCopy.NAME);
    GameRegistry.registerItem(itemComplexDelete, itemComplexDelete.NAME);
    GameRegistry.registerItem(itemComplexMove, itemComplexMove.NAME);

    if (SpeedyToolsOptions.getTesterToolsEnabled()) {
      itemSpeedyTester = new ItemSpeedyTester();
      GameRegistry.registerItem(itemSpeedyTester, itemSpeedyTester.NAME);
    }
  }

  // get a list of all the items
  public static String [] getAllItemNames()
  {
    String[] baseItems = {
            itemSpeedyWandStrong.NAME,
            itemSpeedyWandWeak.NAME,
            itemSpeedySceptre.NAME,
            itemSpeedyOrb.NAME,
            itemSpeedyBoundary.NAME,
            itemComplexCopy.NAME,
            itemComplexDelete.NAME,
            itemComplexMove.NAME};
    if (!SpeedyToolsOptions.getTesterToolsEnabled()) {
      return baseItems;
    }

    // kludge because I can't remember how to initialise ArrayList from String[] and my internet is down...
    String[] debugItems = {
            itemSpeedyWandStrong.NAME,
            itemSpeedyWandWeak.NAME,
            itemSpeedySceptre.NAME,
            itemSpeedyOrb.NAME,
            itemSpeedyBoundary.NAME,
            itemComplexCopy.NAME,
            itemComplexDelete.NAME,
            itemComplexMove.NAME,
            itemSpeedyTester.NAME
    };
    return debugItems;
  }
}
