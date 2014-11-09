package speedytools.common.selections;

import cpw.mods.fml.common.FMLLog;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import speedytools.common.utilities.ErrorLog;

import java.io.ByteArrayOutputStream;

/**
 * User: The Grey Ghost
 * Date: 17/02/14
 *
 * Used to generate a voxel selection from defined region in the world
 * Typical usage:
 * 1) Create a BlockVoxelMultiSelector
 * 2) call selectAllInBoxStart(), selectUnboundFillStart(), or selectBoundFillStart() to set the generation parameters
 * 3) a) repeatedly call continueSelectionGeneration(), providing an optional timeout duration, until complete
 *    b) getEstimatedFractionComplete() can be used to get a rough estimate of task completion
 * 4) After completion:
 *   a) the selection can be retrieved using getSelection().  if isEmpty(), there is no selection
 *   b) any unavailable voxels (eg chunks not loaded on client) are retrieved using getUnavailableVoxels
 *      containsUnavailableVoxels() returns true if there are any unavailable.
 * 5) writeToBytes() can be used to write the selection to a byte array (eg for packet use)
 */
public class BlockVoxelMultiSelector
{
//  public enum Matcher {ALL_NON_AIR, STARTING_BLOCK_ONLY}

  /**
   * initialise conversion of the selected box to a VoxelSelection
   *
   * @param world
   * @param corner1 one corner of the box
   * @param corner2 opposite corner of the box
   */
  public void selectAllInBoxStart(World world, ChunkCoordinates corner1, ChunkCoordinates corner2) {
    initialiseSelectionSizeFromBoundary(corner1, corner2);
//    xpos = 0;
//    ypos = 0;
//    zpos = 0;
    voxelIterator = new VoxelChunkwiseIterator(wxOrigin, wyOrigin, wzOrigin, xSize, ySize, zSize);
    matcher = new FillMatcher.AnyNonAir();
    mode = OperationInProgress.ALL_IN_BOX;
    initialiseVoxelRange();
  }

  /**
   * initialise conversion of the selected fill to a VoxelSelection
   * From the starting block, performs a flood fill on all non-air blocks.
   *
   * @param world
   */
//  public void selectUnboundFillStart(World world, ChunkCoordinates blockUnderCursor, Matcher i_matcher) {
  public void selectUnboundFillStart(World world, FillAlgorithmSettings fillAlgorithmSettings) {
    ChunkCoordinates corner1 = new ChunkCoordinates();
    ChunkCoordinates corner2 = new ChunkCoordinates();
    ChunkCoordinates blockUnderCursor = fillAlgorithmSettings.getStartPosition();
    final int BORDER_ALLOWANCE = 2;
    final int MAXIMUM_Y = 255;
    final int MINIMUM_Y = 0;
    corner1.posX = blockUnderCursor.posX - VoxelSelection.MAX_X_SIZE / 2 + BORDER_ALLOWANCE;
    corner2.posX = blockUnderCursor.posX + VoxelSelection.MAX_X_SIZE / 2 - BORDER_ALLOWANCE;
    corner1.posY = fillAlgorithmSettings.isAutomaticLowerBound() ? blockUnderCursor.posY : MINIMUM_Y;
    corner1.posY = Math.max(MINIMUM_Y, corner1.posY);
    corner2.posY = Math.min(MAXIMUM_Y, corner1.posY + VoxelSelection.MAX_Y_SIZE - 2 * BORDER_ALLOWANCE);
    corner1.posZ = blockUnderCursor.posZ - VoxelSelection.MAX_Z_SIZE / 2 + BORDER_ALLOWANCE;
    corner2.posZ = blockUnderCursor.posZ + VoxelSelection.MAX_Z_SIZE / 2 - BORDER_ALLOWANCE;

    selectBoundFillStart(world, fillAlgorithmSettings, corner1, corner2);
  }

  /**
   * initialise conversion of the selected fill to a VoxelSelection
   * From the starting block, performs a flood fill on all non-air blocks.
   * Will not fill any blocks outside of the box defined by corner1 and corner2
   *
   * @param world
   */
//  public void selectBoundFillStart(World world, ChunkCoordinates blockUnderCursor, Matcher i_matcher, ChunkCoordinates corner1, ChunkCoordinates corner2) {
  public void selectBoundFillStart(World world, FillAlgorithmSettings fillAlgorithmSettings, ChunkCoordinates corner1, ChunkCoordinates corner2) {
    initialiseSelectionSizeFromBoundary(corner1, corner2);
    ChunkCoordinates blockUnderCursor = fillAlgorithmSettings.getStartPosition();
    assert (blockUnderCursor.posX >= wxOrigin && blockUnderCursor.posY >= wyOrigin && blockUnderCursor.posZ >= wzOrigin);
    assert (blockUnderCursor.posX < wxOrigin + xSize && blockUnderCursor.posY < wyOrigin + ySize && blockUnderCursor.posZ < wzOrigin + zSize);
    mode = OperationInProgress.FILL;
    initialiseVoxelRange();
    IVoxelIterator newIterator = null;
    switch(fillAlgorithmSettings.getPropagation()) {
      case FLOODFILL: {
        VoxelChunkwiseFillIterator newVCFIterator = new VoxelChunkwiseFillIterator(wxOrigin, wyOrigin, wzOrigin, xSize, ySize, zSize);
        newVCFIterator.setStartPosition(blockUnderCursor.posX, blockUnderCursor.posY, blockUnderCursor.posZ);
        newVCFIterator.setDiagonalAllowed(fillAlgorithmSettings.isDiagonalPropagationAllowed());
        newIterator = newVCFIterator;
        break;
      }
      case CONTOUR: {
        VoxelChunkwiseContourIterator newVCCIterator = new VoxelChunkwiseContourIterator(wxOrigin, wyOrigin, wzOrigin, xSize, ySize, zSize);
        newVCCIterator.setStartPositionAndPlane(blockUnderCursor.posX, blockUnderCursor.posY, blockUnderCursor.posZ, fillAlgorithmSettings.getNormalDirection());
        newVCCIterator.setDiagonalAllowed(fillAlgorithmSettings.isDiagonalPropagationAllowed());
        newIterator = newVCCIterator;
        break;
      }
      default: {
        ErrorLog.defaultLog().debug("Illegal propagation:" + fillAlgorithmSettings.getPropagation());
        break;
      }
    }

    matcher = fillAlgorithmSettings.getFillMatcher();
//    blockToMatch = new BlockWithMetadata();
//    blockToMatch.block = world.getBlock(blockUnderCursor.posX, blockUnderCursor.posY, blockUnderCursor.posZ);
//    blockToMatch.metaData = world.getBlockMetadata(blockUnderCursor.posX, blockUnderCursor.posY, blockUnderCursor.posZ);
    voxelIterator = newIterator;
    mode = OperationInProgress.FILL;
  }

  /**
   * continue conversion of the selected box to a VoxelSelection.  Call repeatedly until conversion complete.
   *
   * @param world
   * @param maxTimeInNS maximum elapsed duration before processing stops & function returns
   * @return fraction complete (0 - 1), -ve number for finished
   */
  public float continueSelectionGeneration(World world, long maxTimeInNS) {
    if (mode == OperationInProgress.IDLE) {
      FMLLog.severe("Mode should be not be IDLE in BlockVoxelMultiSelector::selectFillContinue");
      return -1;
    }
    if (mode == OperationInProgress.COMPLETE) return -1;

    long startTime = System.nanoTime();
//    System.out.print("Chunks ");

    while (!voxelIterator.isAtEnd()) {
//      System.out.print("[" + voxelIterator.getChunkX() + ", " + voxelIterator.getChunkZ() + "] ");
      voxelIterator.hasEnteredNewChunk();  // reset flag
      Chunk currentChunk = world.getChunkFromChunkCoords(voxelIterator.getChunkX(), voxelIterator.getChunkZ());
      boolean voxelIsUnloaded = false;
      if (currentChunk.isEmpty()) {
        voxelIsUnloaded = true;
      } else {
        while (!voxelIterator.isAtEnd() && !voxelIterator.hasEnteredNewChunk()) {
          FillMatcher.MatchResult matchResult = FillMatcher.MatchResult.NO_MATCH;
          matchResult = matcher.matches(currentChunk, voxelIterator.getWX() & 0x0f, voxelIterator.getWY(), voxelIterator.getWZ() & 0x0f);
          if (matchResult == FillMatcher.MatchResult.OUT_OF_BOUNDS) {
            matchResult = matcher.matches(world, voxelIterator.getWX(), voxelIterator.getWY(), voxelIterator.getWZ());
          }
          switch (matchResult) {
            case MATCH: {
              selection.setVoxel(voxelIterator.getXpos(), voxelIterator.getYpos(), voxelIterator.getZpos());
              expandVoxelRange(voxelIterator.getXpos(), voxelIterator.getYpos(), voxelIterator.getZpos());
              voxelIterator.next(true);
              break;
            }
            case NO_MATCH: {
              voxelIterator.next(false);
              break;
            }
            case NOT_LOADED: {
              voxelIsUnloaded = true;
              break;
            }
            default: {
              ErrorLog.defaultLog().debug("Illegal matchResult:" + matchResult);
            }
          }
//          Block block = currentChunk.getBlock(voxelIterator.getWX() & 0x0f, voxelIterator.getWY(), voxelIterator.getWZ() & 0x0f);
//          boolean matches = false;
//          switch (matcher) {
//            case ALL_NON_AIR: {
//              matches = (block != Blocks.air);
//              break;
//            }
//            case STARTING_BLOCK_ONLY: {
//              int metadata = currentChunk.getBlockMetadata(voxelIterator.getWX() & 0x0f, voxelIterator.getWY(), voxelIterator.getWZ() & 0x0f);
//              matches = (block == blockToMatch.block) && (metadata == blockToMatch.metaData);
//              break;
//            }
//            default: {
//              ErrorLog.defaultLog().severe("Illegal matcher:" + matcher);
//              break;
//            }
//          }
        }
      }
      if (voxelIsUnloaded) {
        containsUnavailableVoxels = true;
        while (!voxelIterator.isAtEnd() && !voxelIterator.hasEnteredNewChunk()) {
          unavailableVoxels.setVoxel(voxelIterator.getXpos(), voxelIterator.getYpos(), voxelIterator.getZpos());
          expandVoxelRange(voxelIterator.getXpos(), voxelIterator.getYpos(), voxelIterator.getZpos());
          voxelIterator.next(false);
        }
      }
      if (System.nanoTime() - startTime >= maxTimeInNS) {
        return voxelIterator.estimatedFractionComplete();
      }
    }

    voxelIterator = null;
    mode = OperationInProgress.COMPLETE;
    shrinkToSmallestEnclosingCuboid();
    return -1;
//
//
//        boolean blockIsAir = world.isAirBlock(checkPosition.posX + wxOrigin, checkPosition.posY + wyOrigin, checkPosition.posZ + wzOrigin);
//        if (!blockIsAir) {
//          ChunkCoordinates newChunkCoordinate = new ChunkCoordinates(checkPosition);
//          SearchPosition nextSearchPosition = new SearchPosition(newChunkCoordinate);
//          nextDepthSearchPositions.addLast(nextSearchPosition);
//          selection.setVoxel(checkPosition.posX, checkPosition.posY, checkPosition.posZ);
//          expandVoxelRange(checkPosition.posX, checkPosition.posY, checkPosition.posZ);
//          ++blocksAddedCount;
//        }
//      }
//
//
//      }
//    }
//
//    mode = OperationInProgress.COMPLETE;
//    shrinkToSmallestEnclosingCuboid();
//    return -1;
  }

  public VoxelSelectionWithOrigin getSelection() {
    return selection;
  }

//  public float continueSelectionGeneration(World world, long maxTimeInNS)
//  {
//    switch (mode) {
//      case ALL_IN_BOX: {
//        return selectAllInBoxContinue(world, maxTimeInNS);
//      }
//      case FILL: {
//        return selectFillContinue(world, maxTimeInNS);
//      }
//      case COMPLETE: {
//        return -1;
//      }
//      default: assert false : "invalid mode " + mode + " in continueSelectionGeneration";
//    }
//    return 0;
//  }

//  public float continueSelectionGeneration(World world, long maxTimeInNS)
//  {
//    switch (mode) {
//      case ALL_IN_BOX: {
//        return selectAllInBoxContinue(world, maxTimeInNS);
//      }
//      case FILL: {
//        return selectFillContinueNEW(world, maxTimeInNS);
//      }
//      case COMPLETE: {
//        return -1;
//      }
//      default: assert false : "invalid mode " + mode + " in continueSelectionGeneration";
//    }
//    return 0;
//  }

  public VoxelSelectionWithOrigin getUnavailableVoxels() {
    return unavailableVoxels;
  }

  public boolean containsUnavailableVoxels() {
    return containsUnavailableVoxels;
  }

  public float getEstimatedFractionComplete() {
    if (voxelIterator == null) return -1;
    return voxelIterator.estimatedFractionComplete();
  }

  /**
   * returns true if there are no solid pixels at all in this selection.
   *
   * @return
   */
  public boolean isEmpty() {
    return empty;
  }

  /**
   * gets the origin for the selection in world coordinates
   *
   * @return the origin for the selection in world coordinates
   */
  public ChunkCoordinates getWorldOrigin() {
    return new ChunkCoordinates(selection.getWxOrigin(), selection.getWyOrigin(), selection.getWzOrigin());
  }


  /**
   * write the current selection in serialised form to a ByteArray
   *
   * @return the byte array, or null for failure
   */
  public ByteArrayOutputStream writeToBytes() {
    return selection.writeToBytes();
  }

  private void initialiseVoxelRange() {
    smallestVoxelX = xSize;
    largestVoxelX = -1;
    smallestVoxelY = ySize;
    largestVoxelY = -1;
    smallestVoxelZ = zSize;
    largestVoxelZ = -1;
    empty = true;
    containsUnavailableVoxels = false;
  }

  private void expandVoxelRange(int x, int y, int z) {
    smallestVoxelX = Math.min(smallestVoxelX, x);
    smallestVoxelY = Math.min(smallestVoxelY, y);
    smallestVoxelZ = Math.min(smallestVoxelZ, z);
    largestVoxelX = Math.max(largestVoxelX, x);
    largestVoxelY = Math.max(largestVoxelY, y);
    largestVoxelZ = Math.max(largestVoxelZ, z);
    empty = false;
  }

  /**
   * shrinks the voxel selection to the minimum size needed to contain the set voxels
   */
  private void shrinkToSmallestEnclosingCuboid() {
    if (smallestVoxelX == 0 && smallestVoxelY == 0 && smallestVoxelZ == 0
            && largestVoxelX == xSize - 1 && largestVoxelY == ySize - 1 && largestVoxelZ == zSize - 1) {
      return;
    }
    if (smallestVoxelX > largestVoxelX) { // is empty!
      smallestVoxelX = 0;
      largestVoxelX = 0;
      smallestVoxelY = 0;
      largestVoxelY = 0;
      smallestVoxelZ = 0;
      largestVoxelZ = 0;
    }

    int newXsize = largestVoxelX - smallestVoxelX + 1;
    int newYsize = largestVoxelY - smallestVoxelY + 1;
    int newZsize = largestVoxelZ - smallestVoxelZ + 1;
    VoxelSelectionWithOrigin smallerSelection = new VoxelSelectionWithOrigin(
            wxOrigin + smallestVoxelX, wyOrigin + smallestVoxelY, wzOrigin + smallestVoxelZ,
            newXsize, newYsize, newZsize);
    for (int y = 0; y < newYsize; ++y) {
      for (int z = 0; z < newZsize; ++z) {
        for (int x = 0; x < newXsize; ++x) {
          if (selection.getVoxel(x + smallestVoxelX, y + smallestVoxelY, z + smallestVoxelZ)) {
            smallerSelection.setVoxel(x, y, z);
          }
        }
      }
    }
    selection = smallerSelection;

    VoxelSelectionWithOrigin smallerUnavailableVoxels = new VoxelSelectionWithOrigin(
            wxOrigin + smallestVoxelX, wyOrigin + smallestVoxelY, wzOrigin + smallestVoxelZ,
            newXsize, newYsize, newZsize);
    for (int y = 0; y < newYsize; ++y) {
      for (int z = 0; z < newZsize; ++z) {
        for (int x = 0; x < newXsize; ++x) {
          if (unavailableVoxels.getVoxel(x + smallestVoxelX, y + smallestVoxelY, z + smallestVoxelZ)) {
            smallerUnavailableVoxels.setVoxel(x, y, z);
          }
        }
      }
    }
    unavailableVoxels = smallerUnavailableVoxels;

    wxOrigin += smallestVoxelX;
    wyOrigin += smallestVoxelY;
    wzOrigin += smallestVoxelZ;
    smallestVoxelX = 0;
    smallestVoxelY = 0;
    smallestVoxelZ = 0;
    largestVoxelX = newXsize - 1;
    largestVoxelY = newYsize - 1;
    largestVoxelZ = newZsize - 1;
    xSize = newXsize;
    ySize = newYsize;
    zSize = newZsize;
  }

  private void initialiseSelectionSizeFromBoundary(ChunkCoordinates corner1, ChunkCoordinates corner2) {
    wxOrigin = Math.min(corner1.posX, corner2.posX);
    wyOrigin = Math.min(corner1.posY, corner2.posY);
    wzOrigin = Math.min(corner1.posZ, corner2.posZ);
    xSize = 1 + Math.max(corner1.posX, corner2.posX) - wxOrigin;
    ySize = 1 + Math.max(corner1.posY, corner2.posY) - wyOrigin;
    zSize = 1 + Math.max(corner1.posZ, corner2.posZ) - wzOrigin;
    if (selection == null) {
      selection = new VoxelSelectionWithOrigin(wxOrigin, wyOrigin, wzOrigin, xSize, ySize, zSize);
      unavailableVoxels = new VoxelSelectionWithOrigin(wxOrigin, wyOrigin, wzOrigin, xSize, ySize, zSize);
    } else {
      selection.resizeAndClear(xSize, ySize, zSize);
      unavailableVoxels.resizeAndClear(xSize, ySize, zSize);
    }
  }

  private enum OperationInProgress
  {
    IDLE, ALL_IN_BOX, FILL, COMPLETE
  }

  private VoxelSelectionWithOrigin selection;
  private VoxelSelectionWithOrigin unavailableVoxels;

  private boolean containsUnavailableVoxels;

  private int smallestVoxelX;
  private int largestVoxelX;
  private int smallestVoxelY;
  private int largestVoxelY;
  private int smallestVoxelZ;
  private int largestVoxelZ;

  private int xSize;
  private int ySize;
  private int zSize;
  private int wxOrigin;
  private int wyOrigin;
  private int wzOrigin;

  private IVoxelIterator voxelIterator;

  private boolean empty = true;
  private OperationInProgress mode;
  private FillMatcher matcher;
//  private BlockWithMetadata blockToMatch;
}
