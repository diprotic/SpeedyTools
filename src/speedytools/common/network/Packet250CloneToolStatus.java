package speedytools.common.network;


import cpw.mods.fml.relauncher.Side;
import net.minecraft.network.packet.Packet250CustomPayload;
import speedytools.common.utilitiesOld.ErrorLog;

import java.io.*;

/**
 * This class is used to inform the client and server of each other's status (primarily for cloning)
 */
public class Packet250CloneToolStatus
{
  public static Packet250CloneToolStatus serverStatusChange(ServerStatus newStatus, byte newPercentage, String newNameOfPlayerBeingServiced)
  {
    return new Packet250CloneToolStatus(null, newStatus, newPercentage, newNameOfPlayerBeingServiced);
  }

  public static Packet250CloneToolStatus clientStatusChange(ClientStatus newStatus)
  {
    return new Packet250CloneToolStatus(newStatus, null, (byte)100, "");
  }

  /**
   * get the custom packet for this status packet
   * @return null for failure
   */
  public Packet250CustomPayload getPacket250CustomPayload()
  {
    checkInvariants();
    Packet250CustomPayload retval = null;
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream outputStream = new DataOutputStream(bos);
      outputStream.writeByte(Packet250Types.PACKET250_TOOL_STATUS_ID.getPacketTypeID());
      outputStream.writeByte(clientStatusToByte(clientStatus));
      outputStream.writeByte(serverStatusToByte(serverStatus));
      outputStream.writeByte(completionPercentage);
      outputStream.writeUTF(nameOfPlayerBeingServiced == null ? "" : nameOfPlayerBeingServiced);
      retval = new Packet250CustomPayload("speedytools",bos.toByteArray());
    } catch (IOException ioe) {
      ErrorLog.defaultLog().warning("Failed to getPacket250CustomPayload, due to exception " + ioe.toString());
      return null;
    }
    return retval;
  }

  /**
   * Creates a Packet250SpeedyToolUse from Packet250CustomPayload
   * @param sourcePacket250 the packet to create it from
   * @return the new packet, or null for failure
   */
  public static Packet250CloneToolStatus createPacket250ToolStatus(Packet250CustomPayload sourcePacket250)
  {
    DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(sourcePacket250.data));
    Packet250CloneToolStatus newPacket = new Packet250CloneToolStatus();

    try {
      byte packetID = inputStream.readByte();
      if (packetID != Packet250Types.PACKET250_TOOL_STATUS_ID.getPacketTypeID()) return null;

      newPacket.clientStatus = byteToClientStatus(inputStream.readByte());
      newPacket.serverStatus = byteToServerStatus(inputStream.readByte());
      newPacket.completionPercentage = inputStream.readByte();
      newPacket.nameOfPlayerBeingServiced = inputStream.readUTF();
    } catch (IOException ioe) {
      ErrorLog.defaultLog().warning("Exception while reading Packet250SpeedyToolUse: " + ioe);
      return null;
    }
    if (!newPacket.checkInvariants()) return null;
    return newPacket;
  }

  private Packet250CloneToolStatus(ClientStatus newClientStatus, ServerStatus newServerStatus,
                                   byte newPercentage, String newNameOfPlayerBeingServiced
  )
  {
    clientStatus = newClientStatus;
    serverStatus = newServerStatus;
    completionPercentage = newPercentage;
    nameOfPlayerBeingServiced = newNameOfPlayerBeingServiced;
  }

  private static final byte NULL_BYTE_VALUE = Byte.MAX_VALUE;

  /**
   * Is this packet valid to be received and acted on by the given side?
   * @param whichSide
   * @return true if yes
   */
  public boolean validForSide(Side whichSide)
  {
    checkInvariants();
    return (   (clientStatus == null && whichSide == Side.CLIENT)
            || (serverStatus == null & whichSide == Side.SERVER)  );
  }

  public ServerStatus getServerStatus() {
    assert (serverStatus != null);
    return serverStatus;
  }

  public ClientStatus getClientStatus() {
    assert (clientStatus != null);
    return clientStatus;
  }

  public byte getCompletionPercentage() {
    assert (checkInvariants());
    assert (serverStatus != null);
    return completionPercentage;
  }
  public String getNameOfPlayerBeingServiced() {
    assert (serverStatus != null);
    return nameOfPlayerBeingServiced;
  }

  private boolean checkInvariants()
  {
    boolean valid;
    valid = (clientStatus == null || serverStatus == null);
    valid = valid & (clientStatus != null || serverStatus != null);
    valid = valid & (serverStatus == ServerStatus.IDLE
                     || (completionPercentage >= 0 && completionPercentage <= 100) );
    return valid;
  }

  private static ServerStatus byteToServerStatus(byte value)
  {
    if (value < 0 || value >= ServerStatus.allValues.length) return null;
    return ServerStatus.allValues[value];
  }

  private static byte serverStatusToByte(ServerStatus value) throws IOException
  {
    byte retval;

    if (value == null) return NULL_BYTE_VALUE;

    for (retval = 0; retval < ServerStatus.allValues.length; ++retval) {
      if (ServerStatus.allValues[retval] == value) return retval;
    }
    throw new IOException("Invalid command value");
  }

  private static ClientStatus byteToClientStatus(byte value)
  {
    if (value < 0 || value >= ServerStatus.allValues.length) return null;
    return ClientStatus.allValues[value];
  }

  private static byte clientStatusToByte(ClientStatus value) throws IOException
  {
    byte retval;

    if (value == null) return NULL_BYTE_VALUE;
    for (retval = 0; retval < ServerStatus.allValues.length; ++retval) {
      if (ClientStatus.allValues[retval] == value) return retval;
    }
    throw new IOException("Invalid command value");
  }

  private Packet250CloneToolStatus()
  {
  }

  private ClientStatus clientStatus;
  private ServerStatus serverStatus;
  private byte completionPercentage = 100;
  private String nameOfPlayerBeingServiced;
}
