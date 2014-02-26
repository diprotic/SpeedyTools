package speedytools.clientonly;

import cpw.mods.fml.common.FMLLog;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;

import java.util.BitSet;

/**
 * User: The Grey Ghost
 * Date: 15/02/14
 */
public class VoxelSelection
{
  public static final int MAX_X_SIZE = 256;
  public static final int MAX_Y_SIZE = 256;
  public static final int MAX_Z_SIZE = 256;

  public VoxelSelection(int x, int y, int z)
  {
    resize(x, y, z);
  }

  public void clearAll(int x, int y, int z)
  {
    resize(x, y, z);
  }

  /**
   * set the value of this voxel (or does nothing if x,y,z out of range)
   * @param x
   * @param y
   * @param z
   */
  public void setVoxel(int x, int y, int z)
  {
    if (   x < 0 || x >= xsize
        || y < 0 || y >= ysize
        || z < 0 || z >= zsize) {
      return;
    }
   voxels.set(x + xsize *(y + ysize * z) );
  }

  /**
   * set the value of this voxel (or does nothing if x,y,z out of range)
   * @param x
   * @param y
   * @param z
   */
  public void clearVoxel(int x, int y, int z)
  {
    if (   x < 0 || x >= xsize
        || y < 0 || y >= ysize
        || z < 0 || z >= zsize) {
      return;
    }
    voxels.clear(x + xsize * (y + ysize * z));
  }

  /**
   * gets the value of this voxel
   * @param x
   * @param y
   * @param z
   * @return the voxel state, or false if x, y, or z is out of range
   */
  public boolean getVoxel(int x, int y, int z)
  {
    if (   x < 0 || x >= xsize
        || y < 0 || y >= ysize
        || z < 0 || z >= zsize) {
      return false;
    }

    return voxels.get(x + xsize *(y + ysize * z) );
  }

  private void resize(int x, int y, int z)
  {
    if (   x <= 0 || x > MAX_X_SIZE
            || y <= 0 || y > MAX_Y_SIZE
            || z <= 0 || z > MAX_Z_SIZE ) {
      FMLLog.severe("Out-of-range [x,y,z] in VoxelSelection constructor: [%d, %d, %d]", x, y, z);
      x = 1;
      y = 1;
      z = 1;
    }
    xsize = x;
    ysize = y;
    zsize = z;
    if (voxels == null) {
      voxels = new BitSet(xsize * ysize * zsize);     // default to all false
    } else {
      voxels.clear();
    }
  }

  private BitSet voxels;
  private int xsize;
  private int ysize;
  private int zsize;
}