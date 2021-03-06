package speedytools.serverside.worldmanipulation;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.WorldServer;
import speedytools.common.blocks.BlockWithMetadata;
import speedytools.common.selections.VoxelSelection;
import speedytools.common.selections.VoxelSelectionWithOrigin;
import speedytools.common.utilities.Pair;
import speedytools.common.utilities.QuadOrientation;

import java.util.LinkedList;
import java.util.List;

/**
* User: The Grey Ghost
* Date: 27/05/2014
* WorldSelectionUndo is used to record a clone tool change to the World and allow it to be subsequently reversed.
* Each clone tool change to the World is represented by a WorldSelectionUndo instance, and stores
* the old block ID, metadata, NBT (TileEntity) data, and Entity data for the parts of the World which changed.
* WorldHistory is typically used to keep track of a sequence of changes (WorldSelectionUndo instances), for example
*  first A, then B, then C
* The philosophy behind the WorldSelectionUndo is that, if action B is undone, the world should be as close as possible
*   to what it would look like if the action B had never been performed, i.e. the state of the World is rewound to its
*   initial state, followed by action A, then followed by action C.
*   Likewise, when the action B is made permanent using makePermanent(), the world and other WorldSelectionUndo
*   will be changed so that a subsequent undo of A will give the same result as if the World were rewound to the initial state,
*   and then action B is performed followed by action C.
* Under some conditions, out-of-order undos will not return the world exactly to the start condition.  This arises when
* copying the selection causes adjacent blocks to break, and these are then overwritten by an overlapping copy.
* For example:
* There is a torch hanging on a wall block X.
* Action A replaces the torch with a new block;
* Action B replaces block X, which was supporting torch A, with a ladder;
* undo (A) puts the torch back, but it's now next to a ladder so it breaks immediately; then
* undo (B) replaces the support wall X, but the torch has been broken and will not be replaced in this step
* This would be relatively complicated to fix and is unlikely to arise much in practice so it won't be fixed at least for now.
* Typical usage:
* (1) Create an empty WorldSelectionUndo
* (2) writeToWorld() to write the supplied WorldFragment and store the undo information
* (3) undoChanges() to roll back the changes made in writeToWorld.  Should be supplied with a list
*       of WorldSelectionUndo objects that were performed after this one, so that their undo information
*       can be updated.
* (4) makePermanent() to adjust the World and other undo layers to make this undo permanent, typically so that it can
*     be freed up.
*/
public class WorldSelectionUndo
{
  public WorldSelectionUndo()
  {
  }

  /**
   * SHALLOW copy of a WorldSelectionUndo
   * @param source
   */
  public WorldSelectionUndo(WorldSelectionUndo source)
  {
    wxOfOrigin = source.wxOfOrigin;
    wyOfOrigin = source.wyOfOrigin;
    wzOfOrigin = source.wzOfOrigin;
    undoWorldFragment = source.undoWorldFragment;
    changedBlocksMask = source.changedBlocksMask;
  }



  /**
   * writes the given WorldFragment into the world, saving enough information to allow for a subsequent undo
   * @param worldServer
   * @param fragmentToWrite
   * @param i_wxOfOrigin
   * @param i_wyOfOrigin
   * @param i_wzOfOrigin
   */
  public void writeToWorld(WorldServer worldServer, WorldFragment fragmentToWrite, int i_wxOfOrigin, int i_wyOfOrigin, int i_wzOfOrigin)
  {
    QuadOrientation noChange = new QuadOrientation(0, 0, 1, 1);
    writeToWorld(worldServer, fragmentToWrite, i_wxOfOrigin, i_wyOfOrigin, i_wzOfOrigin, noChange);
  }

  /**
   * writes the given WorldFragment into the world, saving enough information to allow for a subsequent undo
   * @param worldServer
   * @param fragmentToWrite
   * @param i_wxOfOrigin
   * @param i_wyOfOrigin
   * @param i_wzOfOrigin
   * @param quadOrientation the orientation of the fragment to place (flip, rotate)
   */
  public void writeToWorld(WorldServer worldServer, WorldFragment fragmentToWrite, int i_wxOfOrigin, int i_wyOfOrigin, int i_wzOfOrigin, QuadOrientation quadOrientation)
  {
    AsynchronousWrite runToCompletionToken = new AsynchronousWrite(worldServer, fragmentToWrite, i_wxOfOrigin, i_wyOfOrigin, i_wzOfOrigin, quadOrientation);
    writeToWorldAsynchronous_do(worldServer, runToCompletionToken);
  }

  /**
   * writes the given WorldFragment into the world, saving enough information to allow for a subsequent undo
   * Runs asynchronously: after the initial call, use the returned token to monitor and advance the task
   *    -> repeatedly call token.setTimeToInterrupt() and token.continueProcessing()
   * @param worldServer
   * @param quadOrientation the orientation of the fragment to place (flip, rotate)
   */
  public AsynchronousToken writeToWorldAsynchronous(WorldServer worldServer, WorldFragment fragmentToWrite, int i_wxOfOrigin, int i_wyOfOrigin, int i_wzOfOrigin, QuadOrientation quadOrientation,
                                                    UniqueTokenID transactionID)
  {
    AsynchronousWrite token = new AsynchronousWrite(worldServer, fragmentToWrite, i_wxOfOrigin, i_wyOfOrigin, i_wzOfOrigin, quadOrientation);
    token.setTimeOfInterrupt(token.IMMEDIATE_TIMEOUT);
    writeToWorldAsynchronous_do(worldServer, token);
    return token;
  }

  private void writeToWorldAsynchronous_do(WorldServer worldServer, AsynchronousWrite state)
  {
    /* algorithm is:
       (1) create a border mask for the fragment to be written, i.e. a mask showing all voxels which are adjacent to a set voxel in the fragment.
       (2) save the world data for the fragment voxels and the border mask voxels
       (3) write the fragment data into the world
       (4) find out which voxels in the border mask were unaffected by the writing into the world, and remove them from the undo mask (changedBlocksMask)

       When aborting:
       If we haven't gotten to the WRITING yet, just erase the changedBlocksMask.  Otherwise, abort the WRITE SUBTASK and proceed with the remaining stages as normal
     */

//    System.out.println("WorldSelectionUndo stage: " + state.getStage() + " fractionComplete:" + state.getFractionComplete());

    WorldFragment fragmentToWrite = state.fragmentToWrite;
    QuadOrientation quadOrientation = state.quadOrientation;
    if (state.getStage() == AsynchronousWriteStages.SETUP) {
      if (state.amIaborting()) {
        state.setStage(AsynchronousWriteStages.COMPLETE);
        changedBlocksMask = new VoxelSelection(1, 1, 1);
        return;
      }
      final int Y_MIN_VALID = 0;
      final int Y_MAX_VALID_PLUS_ONE = 256;

      final int BORDER_WIDTH = 1;
      Pair<Integer, Integer> wxzOriginMove = new Pair<Integer, Integer>(0, 0);

      VoxelSelection voxelSelection = fragmentToWrite.getVoxelsWithStoredData().makeReorientedCopyWithBorder(quadOrientation, BORDER_WIDTH, wxzOriginMove);
      state.borderMask = voxelSelection.generateBorderMask();
      voxelSelection.union(state.borderMask);

      wxOfOrigin = state.wxOrigin - BORDER_WIDTH + wxzOriginMove.getFirst();
      wyOfOrigin = state.wyOrigin - BORDER_WIDTH;
      wzOfOrigin = state.wzOrigin - BORDER_WIDTH + wxzOriginMove.getSecond();

      int wyMax = wyOfOrigin + voxelSelection.getySize();
      if (wyOfOrigin < Y_MIN_VALID || wyMax >= Y_MAX_VALID_PLUS_ONE) {
        voxelSelection.clipToYrange(Y_MIN_VALID - wyOfOrigin, Y_MAX_VALID_PLUS_ONE - 1 - wyOfOrigin);
        state.borderMask.clipToYrange(Y_MIN_VALID - wyOfOrigin, Y_MAX_VALID_PLUS_ONE - 1 - wyOfOrigin);
      }

      state.expandedSelection = new VoxelSelectionWithOrigin(wxOfOrigin, wyOfOrigin, wzOfOrigin, voxelSelection);

      undoWorldFragment = new WorldFragment(state.expandedSelection.getxSize(), state.expandedSelection.getySize(), state.expandedSelection.getzSize());
      AsynchronousToken token = undoWorldFragment.readFromWorldAsynchronous(worldServer, wxOfOrigin, wyOfOrigin, wzOfOrigin, state.expandedSelection);
      state.setSubTask(token);
      state.setStage(AsynchronousWriteStages.READ_UNDO_FRAGMENT);
      if (state.isTimeToInterrupt()) return;
    }

    if (state.getStage() == AsynchronousWriteStages.READ_UNDO_FRAGMENT) {
      if (state.amIaborting()) {
        state.subTask.abortProcessing();
      }
      boolean subTaskFinished = state.executeSubTask();
      if (!subTaskFinished) return;
      if (state.amIaborting()) {
        state.setStage(AsynchronousWriteStages.COMPLETE);
        changedBlocksMask = new VoxelSelection(1, 1, 1);
        return;
      }

      //    fragmentToWrite.writeToWorld(worldServerReader, i_wxOfOrigin, i_wyOfOrigin, i_wzOfOrigin, null, quadOrientation);
      AsynchronousToken token = fragmentToWrite.writeToWorldAsynchronous(worldServer, state.wxOrigin, state.wyOrigin, state.wzOrigin, null, quadOrientation);
      state.setSubTask(token);
      state.setStage(AsynchronousWriteStages.WRITE_FRAGMENT);
      if (state.isTimeToInterrupt()) return;
    }

    if (state.getStage() == AsynchronousWriteStages.WRITE_FRAGMENT) {
      if (state.amIaborting()) {
        state.subTask.abortProcessing();
      }
      boolean subTaskFinished = state.executeSubTask();
      if (!subTaskFinished) return;

//    borderFragmentAfterWrite.readFromWorld(worldServerReader, wxOfOrigin, wyOfOrigin, wzOfOrigin, borderMask);
      state.borderFragmentAfterWrite = new WorldFragment(state.borderMask.getxSize(), state.borderMask.getySize(), state.borderMask.getzSize());
      AsynchronousToken token = state.borderFragmentAfterWrite.readFromWorldAsynchronous(worldServer, wxOfOrigin, wyOfOrigin, wzOfOrigin, state.borderMask);
      state.setSubTask(token);
      state.setStage(AsynchronousWriteStages.UPDATE_MASK);
      if (state.isTimeToInterrupt()) return;
    }

    if (state.getStage() == AsynchronousWriteStages.UPDATE_MASK) {
      boolean subTaskFinished = state.executeSubTask();
      if (!subTaskFinished) return;

      VoxelSelection borderMask = state.borderMask;
      VoxelSelection expandedSelection = state.expandedSelection;
      WorldFragment borderFragmentAfterWrite = state.borderFragmentAfterWrite;

      for (int y = 0; y < borderMask.getySize(); ++y) {
        for (int x = 0; x < borderMask.getxSize(); ++x) {
          for (int z = 0; z < borderMask.getzSize(); ++z) {
            if (borderMask.getVoxel(x, y, z)
                    && borderFragmentAfterWrite.doesVoxelMatch(undoWorldFragment, x, y, z)) {
              expandedSelection.clearVoxel(x, y, z);
            }
          }
        }
      }
      changedBlocksMask = expandedSelection;
      state.setStage(AsynchronousWriteStages.COMPLETE);
    }
  }

  public enum AsynchronousWriteStages
  {
    SETUP(0.1), READ_UNDO_FRAGMENT(0.2), WRITE_FRAGMENT(0.6), UPDATE_MASK(0.1), COMPLETE(0.0);

    AsynchronousWriteStages(double i_durationWeight) {durationWeight = i_durationWeight;}
    public double durationWeight;
  }

  private class AsynchronousWrite implements AsynchronousToken
  {
    @Override
    public boolean isTaskComplete() {
      return currentStage == AsynchronousWriteStages.COMPLETE;
    }

    @Override
    public boolean isTaskAborted() {
      return aborting && isTaskComplete();
    }

    @Override
    public boolean isTimeToInterrupt() {
      return (interruptTimeNS == IMMEDIATE_TIMEOUT || (interruptTimeNS != INFINITE_TIMEOUT && System.nanoTime() >= interruptTimeNS));
    }

    @Override
    public void setTimeOfInterrupt(long timeToStopNS) {
      interruptTimeNS = timeToStopNS;
    }

    @Override
    public void continueProcessing() {
      writeToWorldAsynchronous_do(worldServer, this);
    }

    @Override
    public void abortProcessing() {    // allow the task to continue, but some steps will be cut short
      aborting = true;
    }

    @Override
    public double getFractionComplete()
    {
      return cumulativeCompletion + currentStage.durationWeight * stageFractionComplete;
    }

    public AsynchronousWrite(WorldServer i_worldServer, WorldFragment i_fragmentToWrite, int i_wxOrigin, int i_wyOrigin, int i_wzOrigin, QuadOrientation i_quadOrientation)
    {
      worldServer = i_worldServer;
      wxOrigin = i_wxOrigin;
      wyOrigin = i_wyOrigin;
      wzOrigin = i_wzOrigin;
      fragmentToWrite = i_fragmentToWrite;
      quadOrientation = i_quadOrientation;
      currentStage = AsynchronousWriteStages.SETUP;
      interruptTimeNS = INFINITE_TIMEOUT;
      stageFractionComplete = 0;
      cumulativeCompletion = 0;
      subTask = null;
    }

    public AsynchronousWriteStages getStage() {return currentStage;}
    public void setStage(AsynchronousWriteStages nextStage)
    {
      cumulativeCompletion += currentStage.durationWeight;
      currentStage = nextStage;
    }

    public void setSubTask(AsynchronousToken token) {subTask = token;}
    public void setStageFractionComplete(double completionFraction)
    {
      assert (completionFraction >= 0 && completionFraction <= 1.0);
      stageFractionComplete = completionFraction;
    }

    // returns true if the sub-task is finished
    public boolean executeSubTask() {
      if (subTask == null || subTask.isTaskComplete()) {
        return true;
      }
      subTask.setTimeOfInterrupt(interruptTimeNS);
      subTask.continueProcessing();
      stageFractionComplete = subTask.getFractionComplete();
      return subTask.isTaskComplete();
    }

    public boolean amIaborting() {return aborting;}

    public VoxelSelectionWithOrigin getLockedRegion()
    {
      if (currentStage == AsynchronousWriteStages.SETUP || isTaskComplete()) return null;
      return expandedSelection;
    }

    @Override
    public UniqueTokenID getUniqueTokenID() {
      return uniqueTokenID;
    }

    public final WorldServer worldServer;
    public final int wxOrigin;
    public final int wyOrigin;
    public final int wzOrigin;
    public final QuadOrientation quadOrientation;
    public final WorldFragment fragmentToWrite;

    public VoxelSelectionWithOrigin expandedSelection;
    public VoxelSelection borderMask;
    public WorldFragment borderFragmentAfterWrite;

    private AsynchronousWriteStages currentStage;
    private long interruptTimeNS;
    private double stageFractionComplete;
    private double cumulativeCompletion;
    private AsynchronousToken subTask;
    private boolean aborting = false;
    private final UniqueTokenID uniqueTokenID = new UniqueTokenID();
  }

  /**
   * writes the given list of blocks into the world, saving enough information to allow for a subsequent undo
   * @param worldServer
   * @param entityPlayerMP
   * @param sideToPlace where the player's cursor is pointing when they placed the blocks
   */
  public void writeToWorld(WorldServer worldServer, EntityPlayerMP entityPlayerMP, BlockWithMetadata blockToPlace, EnumFacing sideToPlace, List<BlockPos> blockSelection)
  {
    /* algorithm is:
       (1) create a border mask for the blocks to be written, i.e. a mask showing all voxels which are adjacent to a block in the selection
       (2) save the world data for the fragment voxels and the border mask voxels
       (3) write the fragment data into the world
       (4) find out which voxels in the border mask were unaffected by the writing into the world, and remove them from the undo mask (changedBlocksMask)
     */

    if (blockSelection == null || blockSelection.isEmpty()) return;
    BlockPos firstBlock = blockSelection.get(0);
    int xMin = firstBlock.getX();
    int xMax = firstBlock.getX();
    int yMin = firstBlock.getY();
    int yMax = firstBlock.getY();
    int zMin = firstBlock.getZ();
    int zMax = firstBlock.getZ();

    for (BlockPos blockCoords : blockSelection) {
      xMin = Math.min(xMin, blockCoords.getX());
      xMax = Math.max(xMax, blockCoords.getX());
      yMin = Math.min(yMin, blockCoords.getY());
      yMax = Math.max(yMax, blockCoords.getY());
      zMin = Math.min(zMin, blockCoords.getZ());
      zMax = Math.max(zMax, blockCoords.getZ());
    }
    final int Y_MIN_VALID = 0;
    final int Y_MAX_VALID = 255;

    final int BORDER_WIDTH = 1;
    xMin -= BORDER_WIDTH;
    xMax += BORDER_WIDTH;
    yMin = Math.max(Y_MIN_VALID, yMin - BORDER_WIDTH);
    yMax = Math.min(Y_MAX_VALID, yMax + BORDER_WIDTH);
    zMin -= BORDER_WIDTH;
    zMax += BORDER_WIDTH;
    wxOfOrigin = xMin;
    wyOfOrigin = yMin;
    wzOfOrigin = zMin;
    int xSize = xMax + 1 - xMin;
    int ySize = yMax + 1 - yMin;
    int zSize = zMax + 1 - zMin;
    VoxelSelection expandedSelection = new VoxelSelection(xSize, ySize, zSize);
    for (BlockPos blockCoords : blockSelection) {
      expandedSelection.setVoxel(blockCoords.getX() - wxOfOrigin, blockCoords.getY() - wyOfOrigin, blockCoords.getZ() - wzOfOrigin);
    }
    VoxelSelection borderMask = expandedSelection.generateBorderMask();
    expandedSelection.union(borderMask);

    undoWorldFragment = new WorldFragment(xSize, ySize, zSize);
    undoWorldFragment.readFromWorld(worldServer, wxOfOrigin, wyOfOrigin, wzOfOrigin, expandedSelection);

    Item blockItem = (blockToPlace.block == null) ? null : Item.getItemFromBlock(blockToPlace.block);
    ItemStack dummyPlaceItemStack = new ItemStack(blockItem == null ? Items.diamond : blockItem);
    for (BlockPos cc : blockSelection) {
      if (blockToPlace.block == null) {
        worldServer.setBlockToAir(cc);
      } else {
        final float DUMMY_HIT_XYZ = 0.5F;
        try {
          IBlockState iBlockState = blockToPlace.block.onBlockPlaced(worldServer, cc,
                                                                     sideToPlace, DUMMY_HIT_XYZ, DUMMY_HIT_XYZ,
                                                                     DUMMY_HIT_XYZ, blockToPlace.metaData,
                                                                     entityPlayerMP);
          worldServer.setBlockState(cc, iBlockState, 1 + 2);
          blockToPlace.block.onBlockPlacedBy(worldServer, cc, iBlockState, entityPlayerMP, dummyPlaceItemStack);
        } catch (IllegalArgumentException iae) {  // some blocks can't be placed (such as DoubleSlab), so
          // just paste it directly
          IBlockState iBlockState = blockToPlace.block.getStateFromMeta(blockToPlace.metaData);
          worldServer.setBlockState(cc, iBlockState, 1 + 2);
        }
      }
    }

    WorldFragment borderFragmentAfterWrite = new WorldFragment(borderMask.getxSize(), borderMask.getySize(), borderMask.getzSize());
    borderFragmentAfterWrite.readFromWorld(worldServer, wxOfOrigin, wyOfOrigin, wzOfOrigin, borderMask);

    for (int y = 0; y < borderMask.getySize(); ++y) {
      for (int x = 0; x < borderMask.getxSize(); ++x) {
        for (int z = 0; z < borderMask.getzSize(); ++z) {
          if (borderMask.getVoxel(x, y, z)
                  && borderFragmentAfterWrite.doesVoxelMatch(undoWorldFragment, x, y, z)) {
            expandedSelection.clearVoxel(x, y, z);
          }
        }
      }
    }
    changedBlocksMask = expandedSelection;
  }

  /**
   * un-does the changes previously made by this WorldSelectionUndo, taking into account any subsequent
   *   undo layers which overlap this one
   * @param worldServer
   * @param subsequentUndoLayers the list of subsequent undo layers
   */
  public void undoChanges(WorldServer worldServer, List<WorldSelectionUndo> subsequentUndoLayers)
  {
    AsynchronousUndo runToCompletionToken = new AsynchronousUndo(worldServer, subsequentUndoLayers);
    undoChangesAsynchronous_do(worldServer, runToCompletionToken);
  }

  /**
   * un-does the changes previously made by this WorldSelectionUndo, taking into account any subsequent
   *   undo layers which overlap this one
   * Runs asynchronously: after the initial call, use the returned token to monitor and advance the task
   *    -> repeatedly call token.setTimeToInterrupt() and token.continueProcessing()
   * Warning - the subsequentUndoLayers must not be changed until processing is completely finished
   * @param worldServer
   * @param subsequentUndoLayers the list of subsequent undo layers
   */
  public AsynchronousToken undoChangesAsynchronous(WorldServer worldServer, List<WorldSelectionUndo> subsequentUndoLayers)
  {
    AsynchronousUndo token = new AsynchronousUndo(worldServer, subsequentUndoLayers);
    token.setTimeOfInterrupt(token.IMMEDIATE_TIMEOUT);
    undoChangesAsynchronous_do(worldServer, token);
    return token;
  }

  public void undoChangesAsynchronous_do(WorldServer worldServer, AsynchronousUndo state)
  {
    /* algorithm is:
       1) remove undoLayers which don't overlap the undoWorldFragment at all (quick cull on x,y,z extents)
       2) for each voxel in the undoWorldFragment, check if any subsequent undo layers overwrite it.
          if yes: change that undo layer voxel
          if no: set that voxel in the write mask
       3) write the undo data to the world using the write mask (of voxels not overlapped by any other layers.)
     */
    if (state.getStage() == AsynchronousUndoStages.SETUP) {
      LinkedList<WorldSelectionUndo> overlappingUndoLayers = new LinkedList<WorldSelectionUndo>();

      for (WorldSelectionUndo undoLayer : state.subsequentUndoLayers) {
        if (wxOfOrigin <= undoLayer.wxOfOrigin + undoLayer.undoWorldFragment.getxCount()
                && wyOfOrigin <= undoLayer.wyOfOrigin + undoLayer.undoWorldFragment.getyCount()
                && wzOfOrigin <= undoLayer.wzOfOrigin + undoLayer.undoWorldFragment.getzCount()
                && wxOfOrigin + undoWorldFragment.getxCount() >= undoLayer.wxOfOrigin
                && wyOfOrigin + undoWorldFragment.getyCount() >= undoLayer.wyOfOrigin
                && wzOfOrigin + undoWorldFragment.getzCount() >= undoLayer.wzOfOrigin
                ) {
          overlappingUndoLayers.add(undoLayer);
        }
      }
      state.setOverlappingUndoLayers(overlappingUndoLayers);
      state.worldWriteMask = new VoxelSelection(undoWorldFragment.getxCount(), undoWorldFragment.getyCount(), undoWorldFragment.getzCount());
      state.setStage(AsynchronousUndoStages.ADJUST_MASK);
      if (state.isTimeToInterrupt()) return;
    }

    if (state.getStage() == AsynchronousUndoStages.ADJUST_MASK) {
      if (!state.amIaborting()) {
        for (int y = 0; y < undoWorldFragment.getyCount(); ++y) {
          for (int x = 0; x < undoWorldFragment.getxCount(); ++x) {
            for (int z = 0; z < undoWorldFragment.getzCount(); ++z) {
              if (changedBlocksMask.getVoxel(x, y, z)) {
                boolean writeVoxelToWorld = true;
                for (WorldSelectionUndo undoLayer : state.getOverlappingUndoLayers()) {
                  if (undoLayer.changedBlocksMask.getVoxel(x + wxOfOrigin - undoLayer.wxOfOrigin,
                          y + wyOfOrigin - undoLayer.wyOfOrigin,
                          z + wzOfOrigin - undoLayer.wzOfOrigin)) {
                    writeVoxelToWorld = false;
                    undoLayer.undoWorldFragment.copyVoxelContents(x + wxOfOrigin - undoLayer.wxOfOrigin,
                            y + wyOfOrigin - undoLayer.wyOfOrigin,
                            z + wzOfOrigin - undoLayer.wzOfOrigin,
                            this.undoWorldFragment, x, y, z);
                    break;
                  }
                }
                if (writeVoxelToWorld) {
                  state.worldWriteMask.setVoxel(x, y, z);
                }
              }
            }
          }
        }
        AsynchronousToken token = undoWorldFragment.writeToWorldAsynchronous(worldServer, wxOfOrigin, wyOfOrigin, wzOfOrigin, state.worldWriteMask);
        state.setSubTask(token);
      }  // !amIaborting()
//      undoWorldFragment.writeToWorld(worldServerReader, wxOfOrigin, wyOfOrigin, wzOfOrigin, worldWriteMask);
      state.setStage(AsynchronousUndoStages.UNDO);
      if (state.isTimeToInterrupt()) return;
    }

    if (state.getStage() == AsynchronousUndoStages.UNDO) {
      if (state.amIaborting()) {
        state.subTask.abortProcessing();
      }
      boolean subTaskFinished = state.executeSubTask();
      if (!subTaskFinished) return;
      state.setStage(AsynchronousUndoStages.COMPLETE);
    }
  }

  public enum AsynchronousUndoStages
  {
    SETUP(0.1), ADJUST_MASK(0.1), UNDO(0.8), COMPLETE(0.0);

    AsynchronousUndoStages(double i_durationWeight) {durationWeight = i_durationWeight;}
    public double durationWeight;
  }

  private class AsynchronousUndo implements AsynchronousToken
  {
    @Override
    public boolean isTaskComplete() {
      return currentStage == AsynchronousUndoStages.COMPLETE;
    }

    @Override
    public boolean isTaskAborted() {
      return aborting && isTaskComplete();
    }

    @Override
    public boolean isTimeToInterrupt() {
      return (interruptTimeNS == IMMEDIATE_TIMEOUT || (interruptTimeNS != INFINITE_TIMEOUT && System.nanoTime() >= interruptTimeNS));
    }

    @Override
    public void setTimeOfInterrupt(long timeToStopNS) {
      interruptTimeNS = timeToStopNS;
    }

    @Override
    public void continueProcessing() {
      undoChangesAsynchronous_do(worldServer, this);
    }

    @Override
    public void abortProcessing() {
      aborting = true;
    }

    @Override
    public double getFractionComplete()
    {
      return cumulativeCompletion + currentStage.durationWeight * stageFractionComplete;
    }

    public AsynchronousUndo(WorldServer i_worldServer, List<WorldSelectionUndo> i_subsequentUndoLayers)
    {
      worldServer = i_worldServer;
      subsequentUndoLayers = i_subsequentUndoLayers;
      currentStage = AsynchronousUndoStages.SETUP;
      interruptTimeNS = INFINITE_TIMEOUT;
      stageFractionComplete = 0;
      cumulativeCompletion = 0;
      subTask = null;
    }

    public AsynchronousUndoStages getStage() {return currentStage;}
    public void setStage(AsynchronousUndoStages nextStage)
    {
      cumulativeCompletion += currentStage.durationWeight;
      currentStage = nextStage;
    }

    public void setSubTask(AsynchronousToken token) {subTask = token;}
    public void setStageFractionComplete(double completionFraction)
    {
      assert (completionFraction >= 0 && completionFraction <= 1.0);
      stageFractionComplete = completionFraction;
    }

    public boolean amIaborting() {return aborting;}

    // returns true if the sub-task is finished
    public boolean executeSubTask() {
      if (subTask == null || subTask.isTaskComplete()) {
        return true;
      }
      subTask.setTimeOfInterrupt(interruptTimeNS);
      subTask.continueProcessing();
      stageFractionComplete = subTask.getFractionComplete();
      return subTask.isTaskComplete();
    }

    public VoxelSelectionWithOrigin getLockedRegion()
    {
      if (isTaskComplete()) return null;
      return new VoxelSelectionWithOrigin(wxOfOrigin, wyOfOrigin, wzOfOrigin, changedBlocksMask);
    }

    @Override
    public UniqueTokenID getUniqueTokenID() {
      return uniqueTokenID;
    }

    public final WorldServer worldServer;
    public final List<WorldSelectionUndo> subsequentUndoLayers;

    public List<WorldSelectionUndo> getOverlappingUndoLayers() {
      return overlappingUndoLayers;
    }

    public void setOverlappingUndoLayers(List<WorldSelectionUndo> overlappingUndoLayers) {
      this.overlappingUndoLayers = overlappingUndoLayers;
    }

    public VoxelSelection worldWriteMask;

    private List<WorldSelectionUndo> overlappingUndoLayers;
    private AsynchronousUndoStages currentStage;
    private long interruptTimeNS;
    private double stageFractionComplete;
    private double cumulativeCompletion;
    private AsynchronousToken subTask;
    private boolean aborting = false;
    private final UniqueTokenID uniqueTokenID = new UniqueTokenID();
  }

  /**
   * deletes this UndoLayer from the list;
   * (integrates this undoLayer into any preceding Layers that overlap it, so that if any of the
   *  preceding layers are undone, the outcome will not overwrite this permanent change.
   *  For example:
   *  Action A followed by Action B.
   *  If Action B is made permanent, and then Action A is undone, the outcome should be as if the
   *  world were rewound to the initial state and then action B performed.
   * @param precedingUndoLayers the list of undo layers before this one, can be in any order
   */
  public void makePermanent(WorldServer worldServer, List<WorldSelectionUndo> precedingUndoLayers)//, List<WorldSelectionUndo> subsequentUndoLayers)
  {
    /* In order to remove this undoLayer completely, we need to propagate undo information backwards
       For example:
       Initial State then Action A then Action B then Action C stores the following undo information
       A[stores initial], B[stores A], C[stores B].
       After making action B permanent, this needs to look like
        C[Stores B]
       In other words: The undo voxel for Action A is deleted because it will always be overwritten by B

       algorithm is:
       1) remove undoLayers which don't overlap the undoWorldFragment at all (quick cull on x,y,z extents)
       2) for each voxel in the undoWorldFragment B, remove the undo information for all overlapping voxels in all preceding layers
     */
    LinkedList<WorldSelectionUndo> precedingOverlaps = new LinkedList<WorldSelectionUndo>();

    for (WorldSelectionUndo undoLayer : precedingUndoLayers) {
      if (wxOfOrigin <= undoLayer.wxOfOrigin + undoLayer.undoWorldFragment.getxCount()
              && wyOfOrigin <= undoLayer.wyOfOrigin + undoLayer.undoWorldFragment.getyCount()
              && wzOfOrigin <= undoLayer.wzOfOrigin + undoLayer.undoWorldFragment.getzCount()
              && wxOfOrigin + undoWorldFragment.getxCount() >= undoLayer.wxOfOrigin
              && wyOfOrigin + undoWorldFragment.getyCount() >= undoLayer.wyOfOrigin
              && wzOfOrigin + undoWorldFragment.getzCount() >= undoLayer.wzOfOrigin
              ) {
        precedingOverlaps.add(undoLayer);
      }
    }

    for (int y = 0; y < undoWorldFragment.getyCount(); ++y) {
      for (int x = 0; x < undoWorldFragment.getxCount(); ++x) {
//        System.out.print(y + ";" + x + ";");
        for (int z = 0; z < undoWorldFragment.getzCount(); ++z) {
//          char symbol = '.';
          if (this.changedBlocksMask.getVoxel(x, y, z)) {
            for (WorldSelectionUndo precedingUndo : precedingOverlaps) {
              precedingUndo.changedBlocksMask.clearVoxel(x, y, z);
            }
          }
        }  // for z
      } // for x
    } // for y

  }

//  /** takes the input blocksToCheck list and removes any blocks that lie within the selection (undoWorldFragment), i.e. that
//   *    might be affected by the operation in progress.
//   * @param blocksToCheck
//   * @return a list of all blocks that aren't affected by the operation in progress
//   */
//  private List<BlockPos> excludeLockedVoxels(List<BlockPos> blocksToCheck)
//  {
//    LinkedList<BlockPos> culledList = new LinkedList<BlockPos>();
//    for (BlockPos coordinate : blocksToCheck) {
//      if (changedBlocksMask.getVoxel(coordinate.posX - wxOfOrigin, coordinate.posY - wyOfOrigin, coordinate.posZ - wzOfOrigin)) {
//        culledList.add(coordinate);
//      }
//    }
//    return culledList;
//  }

//  /**
//   * Split this WorldSelectionUndo into two parts based on the supplied mask:
//   * Two WorldSelectionUndo are the result:
//   * this: all set voxels which were also present in the mask
//   * return value: all set voxels which were not present in the mask
//   * @param mask
//   * @param wxOriginMask world x,y,z of the origin of the mask
//   * @param wyOriginMask
//   * @param wzOriginMask
//   * @return
//   */
//  public WorldSelectionUndo splitByMask(VoxelSelection mask, int wxOriginMask, int wyOriginMask, int wzOriginMask)
//  {
//    WorldSelectionUndo culledCopy = new WorldSelectionUndo(this);
//    culledCopy.changedBlocksMask = changedBlocksMask.splitByMask(mask, wxOriginMask - wxOfOrigin, wyOriginMask - wyOfOrigin, wzOriginMask - wzOfOrigin);
//    return culledCopy;
//  }

  /**
   * Split this WorldSelectionUndo into two parts based on the supplied locked VoxelSelection region
   * Two WorldSelectionUndo are the result:
   * this: all set voxels which were also present in the locked region
   * return value: all set voxels which were not present in the locked region
   * @param lockedRegion - the locked Region to split on
   * @return a new WorldSelectionUndo containing only voxels which aren't in the lockedRegion - shallow copy only
   */
  public WorldSelectionUndo splitByLockedVoxels(VoxelSelectionWithOrigin lockedRegion)
  {
    WorldSelectionUndo unlockedVoxelsCopy = new WorldSelectionUndo(this);
    int wxOffsetMask = lockedRegion.getWxOrigin() - wxOfOrigin;
    int wyOffsetMask = lockedRegion.getWyOrigin() - wyOfOrigin;
    int wzOffsetMask = lockedRegion.getWzOrigin() - wzOfOrigin;
    unlockedVoxelsCopy.changedBlocksMask = changedBlocksMask.splitByMask(lockedRegion, wxOffsetMask, wyOffsetMask, wzOffsetMask);
    return unlockedVoxelsCopy;
  }

  /**
   * returns the undo metadata stored at a particular location (intended for debugging)
   * @param wx  world coordinates
   * @param wy
   * @param wz
   * @return the metadata at this location, or NULL if not stored
   */
  public Integer getStoredMetadata(int wx, int wy, int wz) {
    int x = wx - wxOfOrigin;
    int y = wy - wyOfOrigin;
    int z = wz - wzOfOrigin;
    if (!changedBlocksMask.getVoxel(x, y, z)) return null;
    return undoWorldFragment.getMetadata(x, y, z);
  }

  private WorldFragment undoWorldFragment;
  private VoxelSelection changedBlocksMask;
  private int wxOfOrigin;
  private int wyOfOrigin;
  private int wzOfOrigin;

}
