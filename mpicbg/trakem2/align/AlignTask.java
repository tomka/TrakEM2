/**
 * 
 */
package mpicbg.trakem2.align;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Tile;
import mpicbg.trakem2.align.Align.ParamOptimize;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.transform.InvertibleCoordinateTransform;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;
import ini.trakem2.display.VectorData;
import ini.trakem2.display.VectorDataTransform;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Methods collection to be called from the GUI for alignment tasks.
 *
 */
final public class AlignTask
{
	static protected boolean tilesAreInPlace = false;
	static protected boolean largestGraphOnly = false;
	static protected boolean hideDisconnectedTiles = false;
	static protected boolean deleteDisconnectedTiles = false;
	static protected boolean deform = false;
	
	final static public Bureaucrat alignSelectionTask ( final Selection selection )
	{
		Worker worker = new Worker("Aligning selected images", false, true) {
			public void run() {
				startedWorking();
				try {
					alignSelection( selection );
					Display.repaint(selection.getLayer());
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
			public void cleanup() {
				if (!selection.isEmpty())
					selection.getLayer().getParent().undoOneStep();
			}
		};
		return Bureaucrat.createAndStart( worker, selection.getProject() );
	}


	final static public void alignSelection( final Selection selection )
	{
		List< Patch > patches = new ArrayList< Patch >();
		for ( Displayable d : selection.getSelected() )
			if ( d instanceof Patch ) patches.add( ( Patch )d );

		List< Patch > fixedPatches = new ArrayList< Patch >();

		// Add active Patch, if any, as the nail
		Displayable active = selection.getActive();
		if ( null != active && active instanceof Patch )
			fixedPatches.add( (Patch)active );

		// Add all locked Patch instances to fixedPatches
		for (final Patch patch : patches)
			if ( patch.isLocked() )
				fixedPatches.add( patch );

		alignPatches( patches, fixedPatches );
	}

	final static public Bureaucrat alignPatchesTask ( final List< Patch > patches , final List< Patch > fixedPatches )
	{
		if ( 0 == patches.size())
		{
			Utils.log("Can't align zero patches.");
			return null;
		}
		Worker worker = new Worker("Aligning images", false, true) {
			public void run() {
				startedWorking();
				try {
					alignPatches( patches, fixedPatches );
					Display.repaint();
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
			public void cleanup() {
				patches.get(0).getLayer().getParent().undoOneStep();
			}
		};
		return Bureaucrat.createAndStart( worker, patches.get(0).getProject() );
	}

	/**
	 * @param patches: the list of Patch instances to align, all belonging to the same Layer.
	 * @param fixed: the list of Patch instances to keep locked in place, if any.
	 */
	final static public void alignPatches( final List< Patch > patches , final List< Patch > fixedPatches )
	{
		if ( patches.size() < 2 )
		{
			Utils.log("No images to align.");
			return;
		}

		for ( final Patch patch : fixedPatches )
		{
			if ( !patches.contains( patch ) )
			{
				Utils.log("The list of fixed patches contains at least one Patch not included in the list of patches to align!");
				return;
			}
		}

		//final Align.ParamOptimize p = Align.paramOptimize;
		final GenericDialog gd = new GenericDialog( "Align Tiles" );
		Align.paramOptimize.addFields( gd );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
		gd.addCheckbox( "consider largest graph only", largestGraphOnly );
		gd.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
		gd.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		Align.paramOptimize.readFields( gd );
		tilesAreInPlace = gd.getNextBoolean();
		largestGraphOnly = gd.getNextBoolean();
		hideDisconnectedTiles = gd.getNextBoolean();
		deleteDisconnectedTiles = gd.getNextBoolean();
		
		final Align.ParamOptimize p = Align.paramOptimize.clone();

		alignPatches( p, patches, fixedPatches );
	}

	/** Montage each layer independently, with SIFT.
	 *  Does NOT register layers to each other.
	 *  Considers visible Patches only. */
	final static public Bureaucrat montageLayersTask(final List<Layer> layers) {
		if (null == layers || layers.isEmpty()) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Montaging layers", true) {
			public void exec() {
				//final Align.ParamOptimize p = Align.paramOptimize;
				final GenericDialog gd = new GenericDialog( "Montage Layers" );
				Align.paramOptimize.addFields( gd );
				
				gd.addMessage( "Miscellaneous:" );
				gd.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
				gd.addCheckbox( "consider largest graph only", largestGraphOnly );
				gd.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
				gd.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
				
				gd.showDialog();
				if ( gd.wasCanceled() ) return;
				
				Align.paramOptimize.readFields( gd );
				tilesAreInPlace = gd.getNextBoolean();
				largestGraphOnly = gd.getNextBoolean();
				hideDisconnectedTiles = gd.getNextBoolean();
				deleteDisconnectedTiles = gd.getNextBoolean();
				
				final Align.ParamOptimize p = Align.paramOptimize.clone();
				montageLayers(p, layers);
			}
		}, layers.get(0).getProject());
	}

	final static public void montageLayers(final Align.ParamOptimize p, final List<Layer> layers) {
		int i = 0;
		for (final Layer layer : layers) {
			if (Thread.currentThread().isInterrupted()) return;
			Collection<Displayable> patches = layer.getDisplayables(Patch.class, true);
			if (patches.isEmpty()) continue;
			for (final Displayable patch : patches) {
				if (patch.isLinked() && !patch.isOnlyLinkedTo(Patch.class)) {
					Utils.log("Cannot montage layer " + layer + "\nReason: at least one Patch is linked to non-image data: " + patch);
					continue;
				}
			}
			Utils.log("====\nMontaging layer " + layer);
			Utils.showProgress(((double)i)/layers.size());
			i++;
			alignPatches(p, new ArrayList<Patch>((Collection<Patch>)(Collection)patches), new ArrayList<Patch>());
			Display.repaint(layer);
		}
	}

	final static private class InverseICT implements mpicbg.models.InvertibleCoordinateTransform {
		final mpicbg.models.InvertibleCoordinateTransform ict;
		/** Sets this to the inverse of ict. */
		InverseICT(final mpicbg.models.InvertibleCoordinateTransform ict) {
			this.ict = ict;
		}
		public final float[] apply(final float[] p) {
			float[] q = p.clone();
			applyInPlace(q);
			return q;
		}
		public final float[] applyInverse(final float[] p) {
			float[] q = p.clone();
			applyInverseInPlace(q);
			return q;
		}
		public final void applyInPlace(final float[] p) {
			try {
				ict.applyInverseInPlace(p);
			} catch (NoninvertibleModelException e) { e.printStackTrace(); }
		}
		public final void applyInverseInPlace(final float[] p) {
			ict.applyInPlace(p);
		}
		public final InvertibleCoordinateTransform createInverse() {
			return null;
		}
	}

	final static public void transformPatchesAndVectorData(final Layer layer, final AffineTransform a) {
		AlignTask.transformPatchesAndVectorData((Collection<Patch>)(Collection)layer.getDisplayables(Patch.class),
			new Runnable() { public void run() {
				layer.apply( Patch.class, a );
			}});
	}

	final static public void transformPatchesAndVectorData(final Collection<Patch> patches, final Runnable alignment) {
		// Store transformation data for each Patch
		final Map<Patch,Patch.TransformProperties> tp = new HashMap<Patch,Patch.TransformProperties>();
		for (final Patch patch : patches) tp.put(patch, patch.getTransformPropertiesCopy()); 

		// Align:
		alignment.run();

		// TODO check that alignTiles doesn't change the dimensions/origin of the LayerSet! That would invalidate the table of TransformProperties

		// Apply transforms to all non-image objects that overlapped with each Patch
		// 1 - Sort patches by layer, and patches by stack index within the layer
		final Map<Layer,TreeMap<Integer,Patch>> lm = new HashMap<Layer,TreeMap<Integer,Patch>>();
		for (final Patch patch : patches) {
			TreeMap<Integer,Patch> sp = lm.get(patch.getLayer());
			if (null == sp) {
				sp = new TreeMap<Integer,Patch>();
				lm.put(patch.getLayer(), sp);
			}
			sp.put(patch.getLayer().indexOf(patch), patch);
		}
		// 2 - for each layer, transform the part of each segmentation on top of the Patch, but only the area that has not been used already:
		final ExecutorService exec = Utils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "AlignTask-transformPatchesAndVectorData");
		final Collection<Future> fuslayer = new ArrayList<Future>();
		final Collection<Future> fus = new ArrayList<Future>();
		try {
		for (final Map.Entry<Layer,TreeMap<Integer,Patch>> e : lm.entrySet()) {
			fuslayer.add(exec.submit(new Runnable() { public void run() {
				final Layer layer = e.getKey();
				// The area already processed
				final Area used_area = new Area();
				// The list of transforms to apply to each VectorData
				final Map<VectorData,VectorDataTransform> transforms = new HashMap<VectorData,VectorDataTransform>();
				// The patches, in proper stack index order:
				final List<Patch> sorted = new ArrayList<Patch>(e.getValue().values());
				Collections.reverse(sorted); // so now it's from top to bottom
				for (final Patch patch : sorted) {
					final Patch.TransformProperties props = tp.remove(patch);
					if (null == props) {
						Utils.log("ERROR: could not find any Patch.TransformProperties for patch " + patch);
						continue;
					}
					final Area a = new Area(props.area);
					a.subtract(used_area);
					if (M.isEmpty(a)) {
						Utils.log2("Skipping fully occluded Patch " + patch);
						continue; // Patch fully occluded by other patches
					}
					// Accumulate:
					used_area.add(props.area);
					//
					mpicbg.trakem2.transform.CoordinateTransformList tlist = null;
					// For the remaining area, see who intersects it
					final Set<Displayable> linked = patch.getLinked();
					//Utils.log("Found linked: " + Utils.toString(linked));
					final List<Displayable> ds = layer.getDisplayables(Displayable.class, a, false);
					ds.addAll(layer.getParent().getZDisplayables(ZDisplayable.class, layer, a, false));
					//Utils.log2("Found under area: " + Utils.toString(ds));
					for (final Displayable d : ds) {
						if (d instanceof VectorData) {
							if (null != linked && !linked.contains(d)) {
								Utils.log("Not transforming non-linked object " + d);
								continue;
							}
							if (null == tlist) {
								// Generate a CoordinateTransformList that includes:
								// 1 - an inverted transform from Patch coords to world coords
								// 2 - the CoordinateTransform of the Patch, if any
								// 3 - the AffineTransform of the Patch
								tlist = new CoordinateTransformList();

								final mpicbg.models.InvertibleCoordinateTransformList old = new mpicbg.models.InvertibleCoordinateTransformList();
								if (null != props.ct) {
									final mpicbg.models.TransformMesh mesh = new mpicbg.trakem2.transform.TransformMesh(props.ct, 32, patch.getOWidth(), patch.getOHeight());
									old.add(mesh);
								}
								final mpicbg.models.AffineModel2D old_aff = new mpicbg.models.AffineModel2D();
								old_aff.set(props.at);
								old.add(old_aff);

								tlist.add(new InverseICT(old));

								// The new part:
								final mpicbg.trakem2.transform.CoordinateTransform ct = patch.getCoordinateTransform();
								if (null != ct) tlist.add(ct);
								final mpicbg.models.AffineModel2D new_aff = new mpicbg.models.AffineModel2D();
								new_aff.set(patch.getAffineTransform());
								tlist.add(new_aff);
							}

							//Utils.log("Transforming " + d + " with Patch " + patch);

							VectorDataTransform vdt = transforms.get((VectorData)d);
							if (null == vdt) {
								vdt = new VectorDataTransform(layer);
								transforms.put((VectorData)d, vdt);
							}
							vdt.add(a, tlist);
						}
					}
				}

				// Apply:
				for (final Map.Entry<VectorData,VectorDataTransform> et : transforms.entrySet()) {
					fus.add(exec.submit(new Runnable() { public void run() {
						try {
							et.getKey().apply(et.getValue());
						} catch (Throwable er) {
							Utils.log("ERROR: can't apply transforms to " + et.getKey());
							IJError.print(er);
						}
					}}));
				}
			}}));
		}
		} catch (Throwable t) {
			IJError.print(t);
		} finally {
			Utils.wait(fuslayer);
			Utils.wait(fus);
			exec.shutdown();
		}
	}

	final static public void alignPatches(
			final Align.ParamOptimize p,
			final List< Patch > patches,
			final List< Patch > fixedPatches )
	{
		final List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( p, patches, fixedPatches, tiles, fixedTiles );

		transformPatchesAndVectorData(patches, new Runnable() {
			public void run() {
				alignTiles( p, tiles, fixedTiles, largestGraphOnly );
			}
		});
	}

	final static public void alignTiles(
			final Align.ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles,
			final boolean largestGraphOnly )
	{
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		if ( tilesAreInPlace )
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		else
			AbstractAffineTile2D.pairTiles( tiles, tilePairs );
		
		Align.connectTilePairs( p, tiles, tilePairs, Runtime.getRuntime().availableProcessors() );
		
		if ( Thread.currentThread().isInterrupted() ) return;
		
		List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( tiles );
		
		final List< AbstractAffineTile2D< ? > > interestingTiles;
		if ( largestGraphOnly )
		{
			/* find largest graph. */
			
			Set< Tile< ? > > largestGraph = null;
			for ( Set< Tile< ? > > graph : graphs )
				if ( largestGraph == null || largestGraph.size() < graph.size() )
					largestGraph = graph;
			
			interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			for ( Tile< ? > t : largestGraph )
				interestingTiles.add( ( AbstractAffineTile2D< ? > )t );
			
			if ( hideDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : tiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().setVisible( false );
			if ( deleteDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : tiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().remove( false );
		}
		else
		{
			interestingTiles = tiles;
			
			/**
			 * virtually interconnect disconnected intersecting graphs
			 * 
			 * TODO Not yet tested---Do we need these virtual connections?
			 */
			
//			if ( graphs.size() > 1 && tilesAreInPlace )
//			{
//				for ( AbstractAffineTile2D< ? >[] tilePair : tilePairs )
//					for ( Set< Tile< ? > > graph : graphs )
//						if ( graph.contains( tilePair[ 0 ] ) && !graph.contains( tilePair[ 1 ] ) )
//							tilePair[ 0 ].makeVirtualConnection( tilePair[ 1 ] );
//			}
		}
			
		if ( Thread.currentThread().isInterrupted() ) return;
		
		Align.optimizeTileConfiguration( p, interestingTiles, fixedTiles );
		
		for ( AbstractAffineTile2D< ? > t : interestingTiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
		
		Utils.log( "Montage done." );
	}
	
	
	final static public Bureaucrat alignMultiLayerMosaicTask( final Layer l )
	{
		Worker worker = new Worker( "Aligning multi-layer mosaic", false, true )
		{
			public void run()
			{
				startedWorking();
				try { alignMultiLayerMosaic( l ); }
				catch ( Throwable e ) { IJError.print( e ); }
				finally { finishedWorking(); }
			}
		};
		return Bureaucrat.createAndStart(worker, l.getProject());
	}
	
	
	/**
	 * Align a multi-layer mosaic.
	 * 
	 * @param l the current layer
	 */
	final public static void alignMultiLayerMosaic( final Layer l )
	{
		/* layer range and misc */
		
		final List< Layer > layers = l.getParent().getLayers();
		final String[] layerTitles = new String[ layers.size() ];
		for ( int i = 0; i < layers.size(); ++i )
			layerTitles[ i ] = l.getProject().findLayerThing(layers.get( i )).toString();
		
		final GenericDialog gd1 = new GenericDialog( "Align Multi-Layer Mosaic : Layer Range" );
		
		gd1.addMessage( "Layer Range:" );
		final int sel = layers.indexOf(l);
		gd1.addChoice( "first :", layerTitles, layerTitles[ sel ] );
		gd1.addChoice( "last :", layerTitles, layerTitles[ sel ] );
		
		gd1.addMessage( "Miscellaneous:" );
		gd1.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
		gd1.addCheckbox( "consider largest graph only", largestGraphOnly );
		gd1.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
		gd1.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
		gd1.addCheckbox( "deform layers", deform );
		
		gd1.showDialog();
		if ( gd1.wasCanceled() ) return;
		
		final int first = gd1.getNextChoiceIndex();
		final int last = gd1.getNextChoiceIndex();
		final int d = first < last ? 1 : -1;
		
		tilesAreInPlace = gd1.getNextBoolean();
		largestGraphOnly = gd1.getNextBoolean();
		hideDisconnectedTiles = gd1.getNextBoolean();
		deleteDisconnectedTiles = gd1.getNextBoolean();
		deform = gd1.getNextBoolean();
		
		/* intra-layer parameters */
		
		final GenericDialog gd2 = new GenericDialog( "Align Multi-Layer Mosaic : Intra-Layer" );

		Align.paramOptimize.addFields( gd2 );
		
		gd2.showDialog();
		if ( gd2.wasCanceled() ) return;
		
		Align.paramOptimize.readFields( gd2 );
		
		
		/* cross-layer parameters */
		
		final GenericDialog gd3 = new GenericDialog( "Align Multi-Layer Mosaic : Cross-Layer" );

		Align.param.addFields( gd3 );
		
		gd3.showDialog();
		if ( gd3.wasCanceled() ) return;
		
		Align.param.readFields( gd3 );
		
		Align.ParamOptimize p = Align.paramOptimize.clone();
		Align.Param cp = Align.param.clone();
		Align.ParamOptimize pcp = p.clone();
		pcp.desiredModelIndex = cp.desiredModelIndex;

		final List< Layer > layerRange = new ArrayList< Layer >();
		for ( int i = first; i != last + d; i += d )
			layerRange.add( layers.get( i ) );

		alignMultiLayerMosaicTask( layerRange, cp, p, pcp, tilesAreInPlace, largestGraphOnly, hideDisconnectedTiles, deleteDisconnectedTiles, deform );
	}
	
	final static private boolean alignGraphs(
			final Align.Param p,
			final Layer layer1,
			final Layer layer2,
			final Set< Tile< ? > > graph1,
			final Set< Tile< ? > > graph2 )
	{
		final Align.Param cp = p.clone();
		
		final Selection selection1 = new Selection( null );
		for ( final Tile< ? > tile : graph1 )
			selection1.add( ( ( AbstractAffineTile2D< ? > )tile ).getPatch() );
		final Rectangle graph1Box = selection1.getBox();
		
		final Selection selection2 = new Selection( null );
		for ( final Tile< ? > tile : graph2 )
			selection2.add( ( ( AbstractAffineTile2D< ? > )tile ).getPatch() );
		final Rectangle graph2Box = selection2.getBox();
		
		final int maxLength = Math.max( Math.max( Math.max( graph1Box.width, graph1Box.height ), graph2Box.width ), graph2Box.height );
		//final float scale = ( float )cp.sift.maxOctaveSize / maxLength;
		/* rather ad hoc but we cannot just scale this to maxOctaveSize */
		cp.sift.maxOctaveSize = Math.min( maxLength, 2 * p.sift.maxOctaveSize );
		/* make sure that, despite rounding issues from scale, it is >= image size */
		final float scale = ( float )( cp.sift.maxOctaveSize - 1 ) / maxLength;
		
		cp.maxEpsilon *= scale;
		
		final FloatArray2DSIFT sift = new FloatArray2DSIFT( cp.sift );
		final SIFT ijSIFT = new SIFT( sift );
		final ArrayList< Feature > features1 = new ArrayList< Feature >();
		final ArrayList< Feature > features2 = new ArrayList< Feature >();
		final ArrayList< PointMatch > candidates = new ArrayList< PointMatch >();
		final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
		
		long s = System.currentTimeMillis();
		
		ijSIFT.extractFeatures(
				layer1.getProject().getLoader().getFlatImage( layer1, graph1Box, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, selection1.getSelected( Patch.class ), false, Color.GRAY ).getProcessor(),
				features1 );
		Utils.log( features1.size() + " features extracted for graphs in layer \"" + layer1.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
		
		ijSIFT.extractFeatures(
				layer2.getProject().getLoader().getFlatImage( layer2, graph2Box, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, selection2.getSelected( Patch.class ), false, Color.GRAY ).getProcessor(),
				features2 );
		Utils.log( features2.size() + " features extracted for graphs in layer \"" + layer1.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
		
		boolean modelFound = false;
		if ( features1.size() > 0 && features2.size() > 0 )
		{
			s = System.currentTimeMillis();
			
			FeatureTransform.matchFeatures(
				features1,
				features2,
				candidates,
				cp.rod );

			final AbstractAffineModel2D< ? > model;
			switch ( cp.expectedModelIndex )
			{
			case 0:
				model = new TranslationModel2D();
				break;
			case 1:
				model = new RigidModel2D();
				break;
			case 2:
				model = new SimilarityModel2D();
				break;
			case 3:
				model = new AffineModel2D();
				break;
			default:
				return false;
			}

			try
			{
				modelFound = model.filterRansac(
						candidates,
						inliers,
						1000,
						cp.maxEpsilon,
						cp.minInlierRatio,
						3 * model.getMinNumMatches(),
						3 );
			}
			catch ( NotEnoughDataPointsException e )
			{
				modelFound = false;
			}
			
			if ( modelFound )
			{
				Utils.log( "Model found for graphs in layer \"" + layer1.getTitle() + "\" and \"" + layer2.getTitle() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
				final AffineTransform b = new AffineTransform();
				b.translate( graph2Box.x, graph2Box.y );
				b.scale( 1.0f / scale, 1.0f / scale );
				b.concatenate( model.createAffine() );
				b.scale( scale, scale );
				b.translate( -graph1Box.x, -graph1Box.y);
				
				for ( Displayable d : selection1.getSelected( Patch.class ) )
					d.preTransform( b, false );
				Display.repaint( layer1 );
			}
			else
				IJ.log( "No model found for graphs in layer \"" + layer1.getTitle() + "\" and \"" + layer2.getTitle() + "\"." );
		}
		
		return modelFound;
	}


	public static final void alignMultiLayerMosaicTask(
			final List< Layer > layerRange,
			final Align.Param cp,
			final Align.ParamOptimize p,
			final Align.ParamOptimize pcp,
			final boolean tilesAreInPlace,
			final boolean largestGraphOnly,
			final boolean hideDisconnectedTiles,
			final boolean deleteDisconnectedTiles,
			final boolean deform )
	{

		/* register */
		
		final List< AbstractAffineTile2D< ? > > allTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > allFixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > previousLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final HashMap< Patch, PointMatch > tileCenterPoints = new HashMap< Patch, PointMatch >();
		
		List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = Display.getFront().getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		
		Layer previousLayer = null;
		
		for ( final Layer layer : layerRange )
		{
			/* align all tiles in the layer */
			
			final List< Patch > patches = new ArrayList< Patch >();
			for ( Displayable a : layer.getDisplayables( Patch.class ) )
				if ( a instanceof Patch ) patches.add( ( Patch )a );
			final List< AbstractAffineTile2D< ? > > currentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
			Align.tilesFromPatches( p, patches, fixedPatches, currentLayerTiles, fixedTiles );
			
			alignTiles( p, currentLayerTiles, fixedTiles, false );
			
			/* connect to the previous layer */
			
			
			/* generate tiles with the cross-section model from the current layer tiles */
			/* ------------------------------------------------------------------------ */
			/* TODO step back and make tiles bare containers for a patch and a model such that by changing the model the tile can be reused */
			final HashMap< Patch, AbstractAffineTile2D< ? > > currentLayerPatchTiles = new HashMap< Patch, AbstractAffineTile2D<?> >();
			for ( final AbstractAffineTile2D< ? > t : currentLayerTiles )
				currentLayerPatchTiles.put( t.getPatch(), t );
			
			final List< AbstractAffineTile2D< ? > > csCurrentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			final List< AbstractAffineTile2D< ? > > csFixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
			Align.tilesFromPatches( cp, patches, fixedPatches, csCurrentLayerTiles, csFixedTiles );
			
			final HashMap< Tile< ? >, AbstractAffineTile2D< ? > > tileTiles = new HashMap< Tile< ? >, AbstractAffineTile2D<?> >();
			for ( final AbstractAffineTile2D< ? > t : csCurrentLayerTiles )
				tileTiles.put( currentLayerPatchTiles.get( t.getPatch() ), t );
			
			for ( final AbstractAffineTile2D< ? > t : currentLayerTiles )
			{
				final AbstractAffineTile2D< ? > csLayerTile = tileTiles.get( t );
				csLayerTile.addMatches( t.getMatches() );
				for ( Tile< ? > ct : t.getConnectedTiles() )
					csLayerTile.addConnectedTile( tileTiles.get( ct ) );
			}
			
			/* add a fixed tile only if there was a Patch selected */
			allFixedTiles.addAll( csFixedTiles );
			
			/* first, align connected graphs to each other */
			
			/* graphs in the current layer */
			final List< Set< Tile< ? > > > currentLayerGraphs = AbstractAffineTile2D.identifyConnectedGraphs( csCurrentLayerTiles );
			
//			/* TODO just for visualization */
//			for ( final Set< Tile< ? > > graph : currentLayerGraphs )
//			{
//				Display.getFront().getSelection().clear();
//				Display.getFront().setLayer( ( ( AbstractAffineTile2D< ? > )graph.iterator().next() ).getPatch().getLayer() );
//				
//				for ( final Tile< ? > tile : graph )
//				{
//					Display.getFront().getSelection().add( ( ( AbstractAffineTile2D< ? > )tile ).getPatch() );
//					Display.repaint();
//				}
//				Utils.showMessage( "OK" );
//			}
			
			/* graphs from the whole system that are present in the previous layer */
			final List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( allTiles );
			final HashMap< Set< Tile< ? > >, Set< Tile< ? > > > graphGraphs = new HashMap< Set<Tile<?>>, Set<Tile<?>> >();
			for ( final Set< Tile< ? > > graph : graphs )
			{
				final Set< Tile< ?  > > previousLayerGraph = new HashSet< Tile< ? > >();
				for ( final Tile< ? > tile : previousLayerTiles )
				{
					if ( graph.contains( tile ) )
					{
						graphGraphs.put( graph, previousLayerGraph );
						previousLayerGraph.add( tile );
					}
				}
			}
			final Collection< Set< Tile< ? > > > previousLayerGraphs = graphGraphs.values();
			
//			/* TODO just for visualization */
//			for ( final Set< Tile< ? > > graph : previousLayerGraphs )
//			{
//				Display.getFront().getSelection().clear();
//				Display.getFront().setLayer( ( ( AbstractAffineTile2D< ? > )graph.iterator().next() ).getPatch().getLayer() );
//				
//				for ( final Tile< ? > tile : graph )
//				{
//					Display.getFront().getSelection().add( ( ( AbstractAffineTile2D< ? > )tile ).getPatch() );
//					Display.repaint();
//				}
//				Utils.showMessage( "OK" );
//			}
			
			/* generate snapshots of the graphs and preregister them using the parameters defined in cp */
			final List< AbstractAffineTile2D< ? >[] > crossLayerTilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
			for ( final Set< Tile< ? > > currentLayerGraph : currentLayerGraphs )
			{
				for ( final Set< Tile< ? > > previousLayerGraph : previousLayerGraphs )
				{
					alignGraphs( cp, layer, previousLayer, currentLayerGraph, previousLayerGraph );
					
					/* TODO this is pointless data shuffling just for type incompatibility---fix this at the root */
					final ArrayList< AbstractAffineTile2D< ? > > previousLayerGraphTiles = new ArrayList< AbstractAffineTile2D< ? > >();
					previousLayerGraphTiles.addAll( ( Set )previousLayerGraph );
					
					final ArrayList< AbstractAffineTile2D< ? > > currentLayerGraphTiles = new ArrayList< AbstractAffineTile2D< ? > >();
					currentLayerGraphTiles.addAll( ( Set )currentLayerGraph );
					
					AbstractAffineTile2D.pairOverlappingTiles( previousLayerGraphTiles, currentLayerGraphTiles, crossLayerTilePairs );
				}
			}
			
			
			/* ------------------------------------------------------------------------ */
			
			
			/* this is without the affine/rigid approximation per graph */
			//AbstractAffineTile2D.pairTiles( previousLayerTiles, csCurrentLayerTiles, crossLayerTilePairs );
			
			Align.connectTilePairs( cp, csCurrentLayerTiles, crossLayerTilePairs, Runtime.getRuntime().availableProcessors() );
			
//			for ( final AbstractAffineTile2D< ? >[] tilePair : crossLayerTilePairs )
//			{
//				Display.getFront().setLayer( tilePair[ 0 ].getPatch().getLayer() );
//				Display.getFront().getSelection().clear();
//				Display.getFront().getSelection().add( tilePair[ 0 ].getPatch() );
//				Display.getFront().getSelection().add( tilePair[ 1 ].getPatch() );
//				
//				Utils.showMessage( "1: OK?" );
//				
//				Display.getFront().setLayer( tilePair[ 1 ].getPatch().getLayer() );
//				Display.getFront().getSelection().clear();
//				Display.getFront().getSelection().add( tilePair[ 0 ].getPatch() );
//				Display.getFront().getSelection().add( tilePair[ 1 ].getPatch() );
//				
//				Utils.showMessage( "2: OK?" );
//			}
			
			/* prepare the next loop */
			
			allTiles.addAll( csCurrentLayerTiles );
			previousLayerTiles.clear();
			previousLayerTiles.addAll( csCurrentLayerTiles );
			
			/* optimize */
			Align.optimizeTileConfiguration( pcp, allTiles, allFixedTiles );
			
			for ( AbstractAffineTile2D< ? > t : allTiles )
				t.getPatch().setAffineTransform( t.getModel().createAffine() );
			
			previousLayer = layer;
		}
		
		List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( allTiles );
		
		final List< AbstractAffineTile2D< ? > > interestingTiles;
		if ( largestGraphOnly )
		{
			if ( Thread.currentThread().isInterrupted() ) return;
			
			/* find largest graph. */
			
			Set< Tile< ? > > largestGraph = null;
			for ( Set< Tile< ? > > graph : graphs )
				if ( largestGraph == null || largestGraph.size() < graph.size() )
					largestGraph = graph;
			
			interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			for ( Tile< ? > t : largestGraph )
				interestingTiles.add( ( AbstractAffineTile2D< ? > )t );
			
			if ( hideDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : allTiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().setVisible( false );
			if ( deleteDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : allTiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().remove( false );
		}
		else
			interestingTiles = new ArrayList< AbstractAffineTile2D<?> >( allTiles );
			
		if ( deform )
		{
			/* ############################################ */
			/* experimental: use the center points of all tiles to define a MLS deformation from the pure intra-layer registration to the globally optimal */
			
			Utils.log( "deforming..." );
			
			/* store the center location of each single tile for later deformation */
			for ( final AbstractAffineTile2D< ? > t : interestingTiles )
			{
				final float[] c = new float[]{ ( float )t.getWidth() / 2.0f,( float )t.getHeight() / 2.0f };
				t.getModel().applyInPlace( c );
				final Point q = new Point( c );
				tileCenterPoints.put( t.getPatch(), new PointMatch( q.clone(), q ) );
			}
			
			for ( final Layer layer : layerRange )
			{
				Utils.log( "layer" + layer );
				
				if ( Thread.currentThread().isInterrupted() ) return;

				/* again, align all tiles in the layer */
				
				List< Patch > patches = new ArrayList< Patch >();
				for ( Displayable a : layer.getDisplayables( Patch.class ) )
					if ( a instanceof Patch ) patches.add( ( Patch )a );
				final List< AbstractAffineTile2D< ? > > currentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
				final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
				Align.tilesFromPatches( p, patches, fixedPatches, currentLayerTiles, fixedTiles );
							
				/* add a fixed tile only if there was a Patch selected */
				allFixedTiles.addAll( fixedTiles );
				
				alignTiles( p, currentLayerTiles, fixedTiles, false );
				
				/* for each independent graph do an independent transform */
				final List< Set< Tile< ? > > > currentLayerGraphs = AbstractAffineTile2D.identifyConnectedGraphs( currentLayerTiles );
				for ( final Set< Tile< ? > > graph : currentLayerGraphs )
				{
				
					/* update the tile-center pointmatches */
					final Collection< PointMatch > matches = new ArrayList< PointMatch >();
					final Collection< AbstractAffineTile2D< ? > > toBeDeformedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
					for ( final AbstractAffineTile2D< ? > t : ( Collection< AbstractAffineTile2D< ? > > )( Collection )graph )
					{
						final PointMatch pm = tileCenterPoints.get( t.getPatch() );
						if ( pm == null ) continue;
						
						final float[] pl = pm.getP1().getL();
						pl[ 0 ] = ( float )t.getWidth() / 2.0f;
						pl[ 1 ] = ( float )t.getHeight() / 2.0f;
						t.getModel().applyInPlace( pl );
						matches.add( pm );
						toBeDeformedTiles.add( t );
					}
					
					for ( final AbstractAffineTile2D< ? > t : toBeDeformedTiles )
					{
						if ( Thread.currentThread().isInterrupted() ) return;
						
						try
						{
							final Patch patch = t.getPatch();
							final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
							final AffineTransform pat = new AffineTransform();
							pat.translate( -pbox.x, -pbox.y );
							pat.preConcatenate( patch.getAffineTransform() );
							
							final mpicbg.trakem2.transform.AffineModel2D toWorld = new mpicbg.trakem2.transform.AffineModel2D();
							toWorld.set( pat );
							
							final MovingLeastSquaresTransform mlst = Align.createMLST( matches, 1.0f );
							
							final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
							ctl.add( toWorld );
							ctl.add( mlst );
							ctl.add( toWorld.createInverse() );
							
							patch.appendCoordinateTransform( ctl );
							
							patch.getProject().getLoader().regenerateMipMaps( patch );
						}
						catch ( Exception e )
						{
							e.printStackTrace();
						}
					}
				}
			}
		}
		
		layerRange.get(0).getParent().setMinimumDimensions();
		IJ.log( "Done: register multi-layer mosaic." );
		
		return;
	}


	/** The ParamOptimize object containg all feature extraction and registration model parameters for the "snap" function. */
	static public final Align.ParamOptimize p_snap = Align.paramOptimize.clone();

	/** Find the most overlapping image to @param patch in the same layer where @param patch sits, and snap @param patch and all its linked Displayable objects.
	 *  If a null @param p_snap is given, it will use the AlignTask.p_snap.
	 *  If @param setup is true, it will show a dialog to adjust parameters. */
	static public final Bureaucrat snap(final Patch patch, final Align.ParamOptimize p_snap, final boolean setup) {
		return Bureaucrat.createAndStart(new Worker.Task("Snapping", true) {
			public void exec() {

		final Align.ParamOptimize p = null == p_snap ? AlignTask.p_snap : p_snap;
		if (setup) p.setup("Snap");

		// Collect Patch linked to active
		final List<Displayable> linked_images = new ArrayList<Displayable>();
		for (final Displayable d : patch.getLinkedGroup(null)) {
			if (d.getClass() == Patch.class && d != patch) linked_images.add(d);
		}
		// Find overlapping images
		final List<Patch> overlapping = new ArrayList<Patch>( (Collection<Patch>) (Collection) patch.getLayer().getIntersecting(patch, Patch.class));
		overlapping.remove(patch);
		if (0 == overlapping.size()) return; // nothing overlaps

		// Discard from overlapping any linked images
		overlapping.removeAll(linked_images);

		if (0 == overlapping.size()) {
			Utils.log("Cannot snap: overlapping images are linked to the one to snap.");
			return;
		}

		// flush
		linked_images.clear();

		// Find the image that overlaps the most
		Rectangle box = patch.getBoundingBox(null);
		Patch most = null;
		Rectangle most_inter = null;
		for (final Patch other : overlapping) {
			if (null == most) {
				most = other;
				most_inter = other.getBoundingBox();
				continue;
			}
			Rectangle inter = other.getBoundingBox().intersection(box);
			if (inter.width * inter.height > most_inter.width * most_inter.height) {
				most = other;
				most_inter = inter;
			}
		}
		// flush
		overlapping.clear();

		// Define two lists:
		//  - a list with all involved tiles: the active and the most overlapping one
		final List<Patch> patches = new ArrayList<Patch>();
		patches.add(most);
		patches.add(patch);
		//  - a list with all tiles except the active, to be set as fixed, immobile
		final List<Patch> fixedPatches = new ArrayList<Patch>();
		fixedPatches.add(most);

		// Patch as Tile
		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( p, patches, fixedPatches, tiles, fixedTiles );

		// Pair and connect overlapping tiles
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		Align.connectTilePairs( p, tiles, tilePairs, Runtime.getRuntime().availableProcessors() );

		if ( Thread.currentThread().isInterrupted() ) return;

		Align.optimizeTileConfiguration( p, tiles, fixedTiles );

		for ( AbstractAffineTile2D< ? > t : tiles ) {
			if (t.getPatch() == patch) {
				AffineTransform at = t.getModel().createAffine();
				try {
					at.concatenate(patch.getAffineTransform().createInverse());
					patch.transform(at);
				} catch (java.awt.geom.NoninvertibleTransformException nite) {
					IJError.print(nite);
				}
				break;
			}
		}

		Display.repaint();

		}}, patch.getProject());
	}

	static public final Bureaucrat registerStackSlices(final Patch slice) {
		return Bureaucrat.createAndStart(new Worker.Task("Registering slices", true) {
			public void exec() {

		// build the list
		ArrayList<Patch> slices = slice.getStackPatches();
		if (slices.size() < 2) {
			Utils.log2("Not a stack!");
			return;
		}

		// check that none are linked to anything other than images
		for (final Patch patch : slices) {
			if (!patch.isOnlyLinkedTo(Patch.class)) {
				Utils.log("Can't register: one or more slices are linked to objects other than images.");
				return;
			}
		}

		// ok proceed
		final Align.ParamOptimize p = Align.paramOptimize.clone();
		p.setup("Register stack slices");

		List<Patch> fixedSlices = new ArrayList<Patch>();
		fixedSlices.add(slice);

		alignPatches( p, slices, fixedSlices );

		Display.repaint();

		}}, slice.getProject());
	}
}
