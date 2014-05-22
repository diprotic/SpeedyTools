package speedytools.serverside;

import net.minecraftforge.common.MinecraftForge;
import speedytools.clientside.rendering.SoundsRegistry;
import speedytools.common.network.PacketHandlerRegistry;

/**
 * User: The Grey Ghost
 * Date: 10/03/14
 * Contains the various objects that define the server side
 */
public class ServerSide
{
  public static void initialise()
  {
    packetHandlerRegistry = new PacketHandlerRegistry() ;
    cloneToolServerActions = new CloneToolServerActions();
    cloneToolsNetworkServer = new CloneToolsNetworkServer(packetHandlerRegistry, cloneToolServerActions);
    speedyToolWorldManipulator = new SpeedyToolWorldManipulator(packetHandlerRegistry);
    serverVoxelSelections = new ServerVoxelSelections(packetHandlerRegistry);
  }

  public static void shutdown()
  {
    packetHandlerRegistry = null;
    cloneToolServerActions = null;
    cloneToolsNetworkServer = null;
    speedyToolWorldManipulator = null;
    serverVoxelSelections = null;
  }

  public static CloneToolsNetworkServer getCloneToolsNetworkServer() {
    return cloneToolsNetworkServer;
  }
  public static CloneToolServerActions getCloneToolServerActions() {
    return cloneToolServerActions;
  }
  public static SpeedyToolWorldManipulator getSpeedyToolWorldManipulator() {
    return speedyToolWorldManipulator;
  }


  private static CloneToolsNetworkServer cloneToolsNetworkServer;

  public static ServerVoxelSelections getServerVoxelSelections() {
    return serverVoxelSelections;
  }

  private static ServerVoxelSelections serverVoxelSelections;
  private static CloneToolServerActions cloneToolServerActions;
  private static SpeedyToolWorldManipulator speedyToolWorldManipulator;
  private static PacketHandlerRegistry packetHandlerRegistry;
}
