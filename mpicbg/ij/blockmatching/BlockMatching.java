package mpicbg.ij.blockmatching;

import java.awt.Shape;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import mpicbg.ij.InverseMapping;
import mpicbg.ij.TransformMapping;
import mpicbg.ij.util.Filter;
import mpicbg.ij.util.Util;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

/**
 * Methods for establishing block-based correspondences for given sets of
 * source {@link Point Points}.
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1b
 */
public class BlockMatching
{
	/** &sigma; of the Gaussian kernel required to make an image sampled at &sigma; = 1.6 (see Lowe 2004) */  
	final static private float minSigma = ( float )Math.sqrt( 2.31f );
	final static private float maxCurvature = 10;
	final static private float maxCurvatureRatio = ( maxCurvature + 1 ) * ( maxCurvature + 1 ) / maxCurvature;
	final static private float rod = 0.9f;
	
	private BlockMatching(){}
	
	/**
	 * Estimate the mean intensity of a block.
	 * 
	 * <dl>
	 * <dt>Note:</dt>
	 * <dd>Make sure that the block is fully contained in the image, this will
	 * not be checked by the method for efficiency reasons.</dd>
	 * </dl>
	 * 
	 * @param fp
	 * @param tx
	 * @param ty
	 * @param blockWidth
	 * @param blockHeight
	 * @return
	 */
	static protected float blockMean(
			final FloatProcessor fp,
			final int tx,
			final int ty,
			final int blockWidth,
			final int blockHeight )
	{
		final int width = fp.getWidth();
		final float[] pixels = ( float[] )fp.getPixels();
		
		double sum = 0;
		for ( int y = ty + blockHeight - 1; y >= ty; --y )
		{
			final int ry = y * width;
			for ( int x = tx + blockWidth - 1; x >= tx; --x )
				sum += pixels[ ry + x ];
		}
		return ( float )( sum / blockWidth / blockHeight );
	}
	
	/**
	 * Estimate the intensity variance of a block.
	 * 
	 * <dl>
	 * <dt>Note:</dt>
	 * <dd>Make sure that the block is fully contained in the image, this will
	 * not be checked by the method for efficiency reasons.</dd>
	 * </dl>
	 * 
	 * @param fp
	 * @param tx
	 * @param ty
	 * @param blockWidth
	 * @param blockHeight
	 * @return
	 */
	static protected float blockVariance(
			final FloatProcessor fp,
			final int tx,
			final int ty,
			final int blockWidth,
			final int blockHeight,
			final float mean )
	{
		final int width = fp.getWidth();
		final float[] pixels = ( float[] )fp.getPixels();
		
		double sum = 0;
		for ( int y = ty + blockHeight - 1; y >= ty; --y )
		{
			final int ry = y * width;
			for ( int x = tx + blockWidth - 1; x >= tx; --x )
			{
				final float a = pixels[ ry + x ] - mean;
				sum += a * a;
			}
		}
		return ( float )( sum / ( blockWidth * blockHeight - 1 ) );
	}
	
	/**
     * Estimate {@linkplain PointMatch point correspondences} for a
     * {@link Collection} of {@link Point Points} among two images that are
     * approximately related by an {@link InvertibleCoordinateTransform} using
     * the square difference of pixel intensities as a similarity measure.
     *  
     * @param source
     * @param target
     * @param transform transfers source into target approximately
     * @param blockRadiusX horizontal radius of a block
     * @param blockRadiusY vertical radius of a block
     * @param searchRadiusX horizontal search radius
     * @param searchRadiusY vertical search radius
     * @param sourcePoints
     * @param sourceMatches
     */
    static public void matchByMinimalSquareDifference(
			final FloatProcessor source,
			final FloatProcessor target,
			final InvertibleCoordinateTransform transform,
			final int blockRadiusX,
			final int blockRadiusY,
			final int searchRadiusX,
			final int searchRadiusY,
			final Collection< ? extends Point > sourcePoints,
			final Collection< PointMatch > sourceMatches )
	{
		Util.normalizeContrast( source );
		Util.normalizeContrast( target );
		
		final FloatProcessor mappedTarget = new FloatProcessor( source.getWidth() + 2 * searchRadiusX, source.getHeight() + 2 * searchRadiusY );
		Util.fillWithNaN( mappedTarget );
		
		final TranslationModel2D tTarget = new TranslationModel2D();
		tTarget.set( -searchRadiusX, -searchRadiusY );
		final CoordinateTransformList lTarget = new CoordinateTransformList();
		lTarget.add( tTarget );
		lTarget.add( transform );
		final InverseMapping< ? > targetMapping = new TransformMapping< CoordinateTransform >( lTarget );
		targetMapping.mapInverseInterpolated( target, mappedTarget );
		
		mappedTarget.setMinAndMax( 0, 1 );
		new ImagePlus( "Mapped Target", mappedTarget ).show();
		
		int k = 0;
		for ( final Point p : sourcePoints )
		{
			final float[] s = p.getL();
			final int px = Math.round( s[ 0 ] );
			final int py = Math.round( s[ 1 ] );
			if (
					px - blockRadiusX >= 0 &&
					px + blockRadiusX < source.getWidth() &&
					py - blockRadiusY >= 0 &&
					py + blockRadiusY < source.getHeight() )
			{
				IJ.showProgress( k++, sourcePoints.size() );
				float tx = 0;
				float ty = 0;
				float dMin = Float.MAX_VALUE;
				for ( int ity = -searchRadiusY; ity <= searchRadiusY; ++ity )
					for ( int itx = -searchRadiusX; itx <= searchRadiusX; ++itx )
					{
						float d = 0;
						float n = 0;
						for ( int iy = -blockRadiusY; iy <= blockRadiusY; ++iy )
						{
							final int y = py + iy;
							for ( int ix = -blockRadiusX; ix <= blockRadiusX; ++ix )
							{
								final int x = px + ix;
								final float sf = source.getf( x, y );
								final float tf = mappedTarget.getf( x + itx + searchRadiusX, y + ity + searchRadiusY );
								if ( sf == Float.NaN || tf == Float.NaN )
									continue;
								else
								{
									final float a = sf - tf;
									d += a * a;
									++n;
								}
							}
						}
						if ( n > 0 )
						{
							d /= n;
							if ( d < dMin )
							{
								dMin = d;
								tx = itx;
								ty = ity;
							}
						}
					}
				final float[] t = new float[]{ tx + s[ 0 ], ty + s[ 1 ] };
				System.out.println( k + " : " + tx + ", " + ty );
				transform.applyInPlace( t );
				sourceMatches.add( new PointMatch( p, new Point( t ) ) );
			}
		}
	}
    
    static protected void matchByMaximalPMCC(
    		final FloatProcessor source,
			final FloatProcessor target,
			final int blockRadiusX,
			final int blockRadiusY,
			final int searchRadiusX,
			final int searchRadiusY,
			final float minR,
			final Collection< ? extends Point > sourcePoints,
			final Collection< PointMatch > sourceMatches )
    {
    	final int blockWidth = 2 * blockRadiusX + 1;
    	final int blockHeight = 2 * blockRadiusY + 1;
    	
    	int k = 0;
    	int l = 0;
    	final ImageStack rMapStack = new ImageStack( 2 * searchRadiusX + 1, 2 * searchRadiusY + 1 );
		for ( final Point p : sourcePoints )
		{
			IJ.showProgress( k++, sourcePoints.size() );
			
			final float[] s = p.getL();
			final int px = Math.round( s[ 0 ] );
			final int py = Math.round( s[ 1 ] );
			final int ptx = px - blockRadiusX;
			final int pty = py - blockRadiusY;
			if (
					ptx >= 0 &&
					ptx + blockWidth < source.getWidth() &&
					pty >= 0 &&
					pty + blockHeight < source.getHeight() )
			{
				final float sourceBlockMean = blockMean( source, ptx, pty, blockWidth, blockHeight );
				final float sourceBlockStd = ( float )Math.sqrt( blockVariance( source, ptx, pty, blockWidth, blockHeight, sourceBlockMean ) );
				float tx = 0;
				float ty = 0;
				float rMax = -Float.MAX_VALUE;
				
				final FloatProcessor rMap = new FloatProcessor( 2 * searchRadiusX + 1, 2 * searchRadiusY + 1 );
				
				for ( int ity = -searchRadiusY; ity <= searchRadiusY; ++ity )
				{
					final int ipty = ity + pty + searchRadiusY;
					for ( int itx = -searchRadiusX; itx <= searchRadiusX; ++itx )
					{
						final int iptx = itx + ptx + searchRadiusX;
						
						final float targetBlockMean = blockMean( target, iptx, ipty, blockWidth, blockHeight );
						final float targetBlockStd = ( float )Math.sqrt( blockVariance( target, iptx, ipty, blockWidth, blockHeight, targetBlockMean ) );
						
						float r = 0;
						for ( int iy = 0; iy <= blockHeight; ++iy )
						{
							final int ys = pty + iy;
							final int yt = ipty + iy;
							for ( int ix = 0; ix <= blockWidth; ++ix )
							{
								final int xs = ptx + ix;
								final int xt = iptx + ix;
								r += ( source.getf( xs, ys ) - sourceBlockMean ) * ( target.getf( xt, yt ) - targetBlockMean );
							}
						}
						r /= sourceBlockStd * targetBlockStd * ( blockWidth * blockHeight - 1 );
						if ( r > rMax )
						{
							rMax = r;
							tx = itx;
							ty = ity;
						}
						rMap.setf( itx + searchRadiusX, ity + searchRadiusY, r );
						
					}
				}
				
				/* search and process maxima */
				float bestR = -2.0f;
				float secondBestR = -2.0f;
				float dx = 0, dy = 0, dxx = 0, dyy = 0, dxy = 0; 
				for ( int y = 2 * searchRadiusY - 1; y > 0; --y )
					for ( int x = 2 * searchRadiusX - 1; x > 0; --x )
					{
						final float
							c00, c01, c02,
							c10, c11, c12,
							c20, c21, c22;
						
						c11 = rMap.getf( x, y );
						
						c00 = rMap.getf( x - 1, y - 1 );
						if ( c00 >= c11 ) continue;
						c01 = rMap.getf( x, y - 1 );
						if ( c01 >= c11 ) continue;
						c02 = rMap.getf( x + 1, y - 1 );
						if ( c02 >= c11 ) continue;
						
						c10 = rMap.getf( x - 1, y );
						if ( c10 >= c11 ) continue;
						c12 = rMap.getf( x + 1, y );
						if ( c12 >= c11 ) continue;
						
						c20 = rMap.getf( x - 1, y + 1 );
						if ( c20 >= c11 ) continue;
						c21 = rMap.getf( x, y + 1 );
						if ( c21 >= c11 ) continue;
						c22 = rMap.getf( x + 1, y + 1 );
						if ( c22 >= c11 ) continue;
						
						/* is it better than what we had before? */
						if ( c11 <= bestR )
						{
							if ( c11 > secondBestR )
								secondBestR = c11;
							continue;
						}
						
						secondBestR = bestR;
						bestR = c11;
						
						/* is it good enough? */
						if ( c11 < minR )
							continue;
						
						/* estimate finite derivatives */
						dx = ( c12 - c10 ) / 2.0f;
						dy = ( c21 - c01 ) / 2.0f;
						dxx = c10 - c11 - c11 + c12;
						dyy = c01 - c11 - c11 + c21;
					    dxy = ( c22 - c20 - c02 + c00 ) / 4.0f;						
					}
				
//				IJ.log( "maximum found" );
				
				/* is it good enough? */
				if ( bestR < minR )
					continue;
				
//				IJ.log( "minR test passed" );
				
				/* is there more than one maximum of equal goodness? */
				if ( secondBestR >= 0 && secondBestR / bestR > rod )
					continue;
				
//				IJ.log( "rod test passed" );
				
				/* is it well localized in both x and y? */
				final float det = dxx * dyy - dxy * dxy;
			    final float trace = dxx + dyy;
			    if ( det == 0 || trace * trace / det > maxCurvatureRatio ) continue;
				
//			    IJ.log( "edge test passed" );
			    
			    /* localize by Taylor expansion */
			    /* invert Hessian */
			    final float ixx = dyy / det;
			    final float ixy = -dxy / det;
			    final float iyy = dxx / det;
			    
			    /* calculate offset */
			    final float ox = -ixx * dx - ixy * dy;
			    final float oy = -ixy * dx - iyy * dy;
			    			    
			    if ( ox >= 1 || oy >= 1 )
			    	continue;
			    
//			    IJ.log( "localized" );
				
			    final float[] t = new float[]{ tx + s[ 0 ] + ox, ty + s[ 1 ] + oy };
//				System.out.println( k + " : " + ( tx + ox ) + ", " + ( ty + oy ) + "  => " + rMax );
				sourceMatches.add( new PointMatch( p, new Point( t ) ) );
				
				rMap.setMinAndMax( rMap.getMin(), rMap.getMax() );
				rMapStack.addSlice( "" + ++l, rMap );
			}
		}
		new ImagePlus( "r", rMapStack ).show();
    }
    
    
    /**
     * Estimate {@linkplain PointMatch point correspondences} for a
     * {@link Collection} of {@link Point Points} among two images that are
     * approximately related by an {@link InvertibleCoordinateTransform} using
     * the Pearson product-moment correlation coefficient (PMCC) <i>r</i> of
     * pixel intensities as similarity measure.  Only correspondence candidates
     * with <i>r</i> >= a given threshold are accepted.
     *  
     * @param scaledSource
     * @param target
     * @param scale [0,1]
     * @param transform transfers source into target approximately
     * @param scaledBlockRadiusX horizontal radius of a block
     * @param scaledBlockRadiusY vertical radius of a block
     * @param scaledSearchRadiusX horizontal search radius
     * @param scaledSearchRadiusY vertical search radius
     * @param minR minimal accepted Cross-Correlation coefficient
     * @param sourcePoints
     * @param sourceMatches
     */
    static public void matchByMaximalPMCC(
			FloatProcessor source,
			final FloatProcessor target,
			final float scale,
			final InvertibleCoordinateTransform transform,
			final int blockRadiusX,
			final int blockRadiusY,
			final int searchRadiusX,
			final int searchRadiusY,
			final float minR,
			final Collection< ? extends Point > sourcePoints,
			final Collection< PointMatch > sourceMatches )
	{
    	final int scaledBlockRadiusX = ( int )Math.ceil( scale * blockRadiusX );
    	final int scaledBlockRadiusY = ( int )Math.ceil( scale * blockRadiusY );
    	final int scaledSearchRadiusX = ( int )Math.ceil( scale * searchRadiusX );
    	final int scaledSearchRadiusY = ( int )Math.ceil( scale * searchRadiusY );
    	
    	/* Scale source */
    	final FloatProcessor scaledSource = Filter.downsample( source, scale, 0.5f );
    	Util.normalizeContrast( scaledSource );
    	
    	/* Smooth target with respect to the desired scale */
    	final FloatProcessor smoothedTarget = ( FloatProcessor )target.duplicate();
    	Filter.smoothForScale( smoothedTarget, scale, 0.5f );
    	Util.normalizeContrast( smoothedTarget );
    	
    	FloatProcessor mappedScaledTarget = new FloatProcessor( scaledSource.getWidth() + 2 * scaledSearchRadiusX, scaledSource.getHeight() + 2 * scaledSearchRadiusY );
		Util.fillWithNoise( mappedScaledTarget );
		
		/* Shift relative to the scaled search radius */
		final TranslationModel2D tTarget = new TranslationModel2D();
		tTarget.set( -scaledSearchRadiusX / scale, -scaledSearchRadiusY / scale );
		
		/* Scale */
		final SimilarityModel2D sTarget = new SimilarityModel2D();
		sTarget.set( 1.0f / scale, 0, 0, 0 );
		
		/* Combined transformation */
		final CoordinateTransformList lTarget = new CoordinateTransformList();
		lTarget.add( sTarget );
		lTarget.add( tTarget );
		lTarget.add( transform );
		
		final InverseMapping< ? > targetMapping = new TransformMapping< CoordinateTransform >( lTarget );
		targetMapping.mapInverseInterpolated( smoothedTarget, mappedScaledTarget );
		
//		scaledSource.setMinAndMax( 0, 1 );
//		mappedScaledTarget.setMinAndMax( 0, 1 );
//		new ImagePlus( "Scaled Source non-smoothed", scaledSource.duplicate() ).show();
//		new ImagePlus( "Mapped Target non-smoothed", mappedScaledTarget.duplicate() ).show();
		
		final float[] gaussianKernel = mpicbg.ij.util.Filter.createNormalizedGaussianKernel( minSigma );
		mpicbg.ij.util.Filter.convolveSeparable( scaledSource, gaussianKernel, gaussianKernel );
		mpicbg.ij.util.Filter.convolveSeparable( mappedScaledTarget, gaussianKernel, gaussianKernel );
		
//		scaledSource.setMinAndMax( 0, 1 );
//		mappedScaledTarget.setMinAndMax( 0, 1 );
//		new ImagePlus( "Scaled Source", scaledSource ).show();
//		new ImagePlus( "Mapped Target", mappedScaledTarget ).show();
		
		final Map< Point, Point > scaledSourcePoints = new HashMap< Point, Point>();
		final ArrayList< PointMatch > scaledSourceMatches = new ArrayList< PointMatch >();
		
		for ( final Point p : sourcePoints )
		{
			final float[] l = p.getL().clone();
			l[ 0 ] *= scale;
			l[ 1 ] *= scale;
			scaledSourcePoints.put( new Point( l ), p );
		}
		
		matchByMaximalPMCC(
				scaledSource,
				mappedScaledTarget,
				scaledBlockRadiusX,
				scaledBlockRadiusY,
				scaledSearchRadiusX,
				scaledSearchRadiusY,
				minR,
				scaledSourcePoints.keySet(),
				scaledSourceMatches );
		
		for ( final PointMatch p : scaledSourceMatches )
		{
			final float[] l = p.getP2().getL().clone();
			l[ 0 ] /= scale;
			l[ 1 ] /= scale;
			transform.applyInPlace( l );
			sourceMatches.add( new PointMatch( scaledSourcePoints.get( p.getP1() ), new Point( l ) ) );
		}
	}
    
    
	/**
	 * Create a Shape that illustrates a {@link Collection} of
	 * {@link PointMatch PointMatches}. 
	 * 
	 * @return the illustration
	 */
	static public Shape illustrateMatches( final Collection< PointMatch > matches)
	{
		GeneralPath path = new GeneralPath();
		
		for ( final PointMatch m : matches )
		{
			final float[] w1 = m.getP1().getW();
			final float[] w2 = m.getP2().getW();
			path.moveTo( w1[ 0 ] - 1, w1[ 1 ] - 1 );
			path.lineTo( w1[ 0 ] - 1, w1[ 1 ] + 1 );
			path.lineTo( w1[ 0 ] + 1, w1[ 1 ] + 1 );
			path.lineTo( w1[ 0 ] + 1, w1[ 1 ] - 1 );
			path.closePath();
			path.moveTo( w1[ 0 ], w1[ 1 ] );
			path.lineTo( w2[ 0 ], w2[ 1 ] );
		}
		
		return path;
	}
}
