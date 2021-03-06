/*
 * Copyright 2010-2015 Institut Pasteur.
 * 
 * This file is part of Icy.
 * 
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <http://www.gnu.org/licenses/>.
 */
package icy.sequence;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.undo.UndoManager;

import org.w3c.dom.Node;

import icy.common.CollapsibleEvent;
import icy.common.UpdateEventHandler;
import icy.common.exception.TooLargeArrayException;
import icy.common.listener.ChangeListener;
import icy.file.FileUtil;
import icy.file.SequenceFileGroupImporter;
import icy.gui.viewer.Viewer;
import icy.image.IcyBufferedImage;
import icy.image.IcyBufferedImageEvent;
import icy.image.IcyBufferedImageListener;
import icy.image.IcyBufferedImageUtil;
import icy.image.ImageProvider;
import icy.image.colormap.IcyColorMap;
import icy.image.colormodel.IcyColorModel;
import icy.image.colormodel.IcyColorModelEvent;
import icy.image.colormodel.IcyColorModelListener;
import icy.image.lut.LUT;
import icy.main.Icy;
import icy.math.MathUtil;
import icy.math.Scaler;
import icy.math.UnitUtil;
import icy.math.UnitUtil.UnitPrefix;
import icy.painter.Overlay;
import icy.painter.OverlayEvent;
import icy.painter.OverlayEvent.OverlayEventType;
import icy.painter.OverlayListener;
import icy.painter.OverlayWrapper;
import icy.painter.Painter;
import icy.preferences.GeneralPreferences;
import icy.roi.ROI;
import icy.roi.ROI2D;
import icy.roi.ROI3D;
import icy.roi.ROIEvent;
import icy.roi.ROIListener;
import icy.sequence.SequenceEvent.SequenceEventSourceType;
import icy.sequence.SequenceEvent.SequenceEventType;
import icy.sequence.edit.DataSequenceEdit;
import icy.sequence.edit.DefaultSequenceEdit;
import icy.sequence.edit.MetadataSequenceEdit;
import icy.sequence.edit.ROIAddSequenceEdit;
import icy.sequence.edit.ROIAddsSequenceEdit;
import icy.sequence.edit.ROIRemoveSequenceEdit;
import icy.sequence.edit.ROIRemovesSequenceEdit;
import icy.system.IcyExceptionHandler;
import icy.system.thread.ThreadUtil;
import icy.type.DataType;
import icy.type.TypeUtil;
import icy.type.collection.CollectionUtil;
import icy.type.collection.array.Array1DUtil;
import icy.type.dimension.Dimension5D;
import icy.type.rectangle.Rectangle5D;
import icy.undo.IcyUndoManager;
import icy.undo.IcyUndoableEdit;
import icy.util.OMEUtil;
import icy.util.StringUtil;
import loci.formats.ome.OMEXMLMetadataImpl;
import ome.xml.meta.OMEXMLMetadata;

/**
 * Image sequence object.<br>
 * A <code>Sequence</code> is basically a 5 dimension (XYCZT) image where :<br>
 * XY dimension = planar image<br>
 * C dimension = channel<br>
 * Z dimension = depth<br>
 * T dimension = time<br>
 * <br>
 * The XYC dimensions are bounded into the {@link IcyBufferedImage} object so <code>Sequence</code> define a list of
 * {@link IcyBufferedImage} where each image is associated to a Z and T
 * information.
 * 
 * @author Fabrice de Chaumont & Stephane
 */

public class Sequence implements SequenceModel, IcyColorModelListener, IcyBufferedImageListener, ChangeListener,
        ROIListener, OverlayListener
{
    public static final String DEFAULT_NAME = "no name";

    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_BYTE = TypeUtil.TYPE_BYTE;
    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_DOUBLE = TypeUtil.TYPE_DOUBLE;
    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_FLOAT = TypeUtil.TYPE_FLOAT;
    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_INT = TypeUtil.TYPE_INT;
    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_SHORT = TypeUtil.TYPE_SHORT;
    /**
     * @deprecated
     */
    @Deprecated
    public static final int TYPE_UNDEFINED = TypeUtil.TYPE_UNDEFINED;

    public static final String ID_NAME = "name";

    public static final String ID_POSITION_X = "positionX";
    public static final String ID_POSITION_Y = "positionY";
    public static final String ID_POSITION_Z = "positionZ";
    public static final String ID_POSITION_T = "positionT";
    public static final String ID_POSITION_T_OFFSET = "positionTOffset";

    public static final String ID_PIXEL_SIZE_X = "pixelSizeX";
    public static final String ID_PIXEL_SIZE_Y = "pixelSizeY";
    public static final String ID_PIXEL_SIZE_Z = "pixelSizeZ";
    public static final String ID_TIME_INTERVAL = "timeInterval";

    public static final String ID_CHANNEL_NAME = "channelName";

    public static final String ID_VIRTUAL = "virtual";

    /**
     * id generator
     */
    protected static int id_gen = 1;

    /**
     * volumetric images (4D [XYCZ])
     */
    protected final TreeMap<Integer, VolumetricImage> volumetricImages;
    /**
     * painters
     */
    protected final Set<Overlay> overlays;
    /**
     * ROIs
     */
    protected final Set<ROI> rois;

    /**
     * id of sequence (uniq during an Icy session)
     */
    protected final int id;
    /**
     * colorModel of sequence
     */
    protected IcyColorModel colorModel;
    /**
     * default lut for this sequence
     */
    protected LUT defaultLut;
    /**
     * user lut for this sequence (saved in metadata)
     */
    protected LUT userLut;
    /**
     * Origin filename (from/to which the sequence has been loaded/saved)<br>
     * null --> no file attachment<br>
     * directory or metadata file --> multiples files attachment<br>
     * image file --> single file attachment
     */
    protected String filename;
    /**
     * Returns the {@link ImageProvider} used to load the sequence data.<br>
     * It can return <code>null</code> if the Sequence was not loaded from a specific resource or if it was saved in between.
     */
    protected ImageProvider imageProvider;

    /**
     * Resolution level from the original image<br>
     * 0 --> full image resolution<br>
     * 1 --> resolution / 2<br>
     * 2 --> resolution / 4<br>
     * 3 --> ...<br>
     * Default value is 0
     */
    protected int originResolution;
    /**
     * Region (X,Y) from original image if this image is a crop of the original image.<br>
     * Default value is <code>null</code> (no crop)
     */
    protected Rectangle originXYRegion;
    /**
     * Z range from original image if this image is a crop in Z of the original image.<br>
     * Default value is -1, -1 if we have the whole Z range.
     */
    protected int originZMin;
    protected int originZMax;
    /**
     * T range from original image if this image is a crop in T of the original image.<br>
     * Default value is -1, -1 if we have the whole T range.
     */
    protected int originTMin;
    protected int originTMax;
    /**
     * Channel position from original image if this image is a single channel extraction of the original image.<br>
     * Default value is -1 which mean that all channels were preserved.
     */
    protected int originChannel;

    /**
     * Metadata
     */
    protected OMEXMLMetadata metaData;
    // /**
    // * X, Y, Z resolution (in mm)
    // */
    // private double pixelSizeX;
    // private double pixelSizeY;
    // private double pixelSizeZ;
    // /**
    // * T resolution (in ms)
    // */
    // private double timeInterval;
    // /**
    // * channels name
    // */
    // private String channelsName[];

    // /**
    // * automatic update of component absolute bounds
    // */
    // private boolean componentAbsBoundsAutoUpdate;
    /**
     * automatic update of channel bounds
     */
    protected boolean autoUpdateChannelBounds;
    /**
     * persistent object to load/save data (XML format)
     */
    protected final SequencePersistent persistent;
    /**
     * undo manager
     */
    protected final IcyUndoManager undoManager;

    /**
     * internal updater
     */
    protected final UpdateEventHandler updater;
    /**
     * listeners
     */
    protected final List<SequenceListener> listeners;
    protected final List<SequenceModelListener> modelListeners;

    /**
     * internals
     */
    protected boolean channelBoundsInvalid;

    /**
     * Creates a new empty sequence with specified meta data object and name.
     */
    public Sequence(OMEXMLMetadata meta, String name)
    {
        super();

        // set id
        synchronized (Sequence.class)
        {
            id = id_gen;
            id_gen++;
        }

        // set metadata object
        if (meta == null)
            metaData = MetaDataUtil.createMetadata(name);
        else
            metaData = meta;

        // set name
        if (!StringUtil.isEmpty(name))
            MetaDataUtil.setName(metaData, 0, name);
        else
        {
            // default name
            if (StringUtil.isEmpty(MetaDataUtil.getName(metaData, 0)))
                MetaDataUtil.setName(metaData, 0, DEFAULT_NAME + StringUtil.toString(id, 3));
        }
        filename = null;
        imageProvider = null;

        originResolution = 0;
        originXYRegion = null;
        originZMin = -1;
        originZMax = -1;
        originTMin = -1;
        originTMax = -1;
        originChannel = -1;

        // default pixel size and time interval
        if (Double.isNaN(MetaDataUtil.getPixelSizeX(metaData, 0, Double.NaN)))
            MetaDataUtil.setPixelSizeX(metaData, 0, 1d);
        if (Double.isNaN(MetaDataUtil.getPixelSizeY(metaData, 0, Double.NaN)))
            MetaDataUtil.setPixelSizeY(metaData, 0, 1d);
        if (Double.isNaN(MetaDataUtil.getPixelSizeZ(metaData, 0, Double.NaN)))
            MetaDataUtil.setPixelSizeZ(metaData, 0, 1d);
        if (Double.isNaN(MetaDataUtil.getTimeInterval(metaData, 0, Double.NaN)))
        {
            final double ti = MetaDataUtil.getTimeIntervalFromTimePositions(metaData, 0);
            // we got something --> set it as the time interval
            if (ti != 0d)
                MetaDataUtil.setTimeInterval(metaData, 0, ti);
            // set to 1d by default
            else MetaDataUtil.setTimeInterval(metaData, 0, 1d);
        }
        
        double result = MetaDataUtil.getTimeInterval(metaData, 0, 0d);

        // not yet defined ?
        if (result == 0d)
        {
            result = MetaDataUtil.getTimeIntervalFromTimePositions(metaData, 0);
            // we got something --> set it as the time interval
            if (result != 0d)
                MetaDataUtil.setTimeInterval(metaData, 0, result);
        }
        

        volumetricImages = new TreeMap<Integer, VolumetricImage>();
        overlays = new HashSet<Overlay>();
        rois = new HashSet<ROI>();
        persistent = new SequencePersistent(this);
        undoManager = new IcyUndoManager(this, GeneralPreferences.getHistorySize());

        updater = new UpdateEventHandler(this, false);
        listeners = new ArrayList<SequenceListener>();
        modelListeners = new ArrayList<SequenceModelListener>();

        // no colorModel yet
        colorModel = null;
        defaultLut = null;
        userLut = null;
        channelBoundsInvalid = false;
        // automatic update of channel bounds
        autoUpdateChannelBounds = true;
    }

    /**
     * @deprecated Use {@link #Sequence(OMEXMLMetadata, String)} instead.
     */
    @Deprecated
    public Sequence(OMEXMLMetadataImpl meta, String name)
    {
        this((OMEXMLMetadata) meta, name);
    }

    /**
     * Creates a sequence with specified name and containing the specified image
     */
    public Sequence(String name, IcyBufferedImage image)
    {
        this(name, (BufferedImage) image);
    }

    /**
     * Creates a sequence with specified name and containing the specified image
     */
    public Sequence(String name, BufferedImage image)
    {
        this((OMEXMLMetadata) null, name);

        addImage(image);
    }

    /**
     * @deprecated Use {@link #Sequence(OMEXMLMetadata)} instead.
     */
    @Deprecated
    public Sequence(OMEXMLMetadataImpl meta)
    {
        this((OMEXMLMetadata) meta);
    }

    /**
     * Creates a new empty sequence with specified metadata.
     */
    public Sequence(OMEXMLMetadata meta)
    {
        this(meta, null);
    }

    /**
     * Creates a sequence containing the specified image.
     */
    public Sequence(IcyBufferedImage image)
    {
        this((BufferedImage) image);
    }

    /**
     * Creates a sequence containing the specified image.
     */
    public Sequence(BufferedImage image)
    {
        this((OMEXMLMetadata) null, null);

        addImage(image);
    }

    /**
     * Creates an empty sequence with specified name.
     */
    public Sequence(String name)
    {
        this((OMEXMLMetadata) null, name);
    }

    /**
     * Creates an empty sequence.
     */
    public Sequence()
    {
        this((OMEXMLMetadata) null, null);
    }

    @Override
    protected void finalize() throws Throwable
    {
        // cancel any pending prefetch tasks for this sequence
        SequencePrefetcher.cancel(this);

        try
        {
            // close image provider if needed
            if ((imageProvider != null) && (imageProvider instanceof Closeable))
                ((Closeable) imageProvider).close();
        }
        catch (IOException e)
        {
            // ignore
        }

        super.finalize();
    }

    /**
     * This method close all attached viewers
     */
    public void close()
    {
        Icy.getMainInterface().closeSequence(this);
    }

    /**
     * Called when sequence has been closed (all viewers displaying it closed).<br>
     * <i>Used internally, you should not call it this method directly !</i>
     */
    public void closed()
    {
        // cancel any pending prefetch tasks for this sequence
        SequencePrefetcher.cancel(this);

        // do this in background as it can take sometime
        while (!ThreadUtil.bgRun(new Runnable()
        {
            @Override
            public void run()
            {
                // Sequence persistence enabled --> save XML
                if (GeneralPreferences.getSequencePersistence())
                    saveXMLData();
            }
        }))
        {
            // wait until the process execute
            ThreadUtil.sleep(10L);
        }

        // notify close
        fireClosedEvent();
    }

    /**
     * Copy data and metadata from the specified Sequence
     * 
     * @param source
     *        the source sequence to copy data from
     * @param copyName
     *        if set to <code>true</code> it will also copy the name from the source sequence
     */
    public void copyFrom(Sequence source, boolean copyName)
    {
        copyDataFrom(source);
        copyMetaDataFrom(source, copyName);
    }

    /**
     * Copy data from the specified Sequence
     */
    public void copyDataFrom(Sequence source)
    {
        final int sizeT = source.getSizeT();
        final int sizeZ = source.getSizeZ();

        beginUpdate();
        try
        {
            removeAllImages();
            for (int t = 0; t < sizeT; t++)
            {
                for (int z = 0; z < sizeZ; z++)
                {
                    final IcyBufferedImage img = source.getImage(t, z);

                    if (img != null)
                        setImage(t, z, IcyBufferedImageUtil.getCopy(img));
                    else
                        source.setImage(t, z, null);
                }
            }
        }
        finally
        {
            endUpdate();
        }
    }

    /**
     * Copy metadata from the specified Sequence
     * 
     * @param source
     *        the source sequence to copy metadata from
     * @param copyName
     *        if set to <code>true</code> it will also copy the name from the source sequence
     */
    public void copyMetaDataFrom(Sequence source, boolean copyName)
    {
        // copy all metadata from source
        metaData = OMEUtil.createOMEXMLMetadata(source.getOMEXMLMetadata());

        // restore name if needed
        if (copyName)
            setName(source.getName());

        // notify metadata changed
        metaChanged(null);
    }

    /**
     * Create a complete restore point for this sequence.
     * 
     * @param name
     *        restore point name (visible in the History panel)
     * @return false if for some reason the operation failed (out of memory for instance)
     * @see #undo()
     */
    public boolean createUndoPoint(String name)
    {
        try
        {
            undoManager.addEdit(new DefaultSequenceEdit(SequenceUtil.getCopy(this, false, false, false), this));
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Create a restore point for sequence data.
     * 
     * @param name
     *        restore point name (visible in the History panel)
     * @return false if for some reason the operation failed (out of memory for instance)
     * @see #undo()
     */
    public boolean createUndoDataPoint(String name)
    {
        try
        {
            undoManager.addEdit(new DataSequenceEdit(SequenceUtil.getCopy(this, false, false, false), this, name));
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Create a restore point for sequence metadata.
     * 
     * @param name
     *        restore point name (visible in the History panel)
     * @return false if for some reason the operation failed (out of memory for instance)
     * @see #undo()
     */
    public boolean createUndoMetadataPoint(String name)
    {
        try
        {
            undoManager.addEdit(new MetadataSequenceEdit(OMEUtil.createOMEXMLMetadata(metaData), this, name));
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Add an Undoable edit to the Sequence UndoManager
     * 
     * @param edit
     *        the undoable edit to add
     * @return <code>false</code> if the operation failed
     */
    public boolean addUndoableEdit(IcyUndoableEdit edit)
    {
        if (edit != null)
            return undoManager.addEdit(edit);

        return false;
    }

    /**
     * Undo to the last <i>Undoable</i> change set in the Sequence {@link UndoManager}
     * 
     * @return <code>true</code> if the operation succeed
     * @see #createUndoPoint(String)
     * @see UndoManager#undo()
     */
    public boolean undo()
    {
        if (undoManager.canUndo())
        {
            undoManager.undo();
            return true;
        }

        return false;
    }

    /**
     * Redo the next <i>Undoable</i> change set in the Sequence {@link UndoManager}
     * 
     * @return <code>true</code> if the operation succeed
     * @see #createUndoPoint(String)
     * @see UndoManager#redo()
     */
    public boolean redo()
    {
        if (undoManager.canRedo())
        {
            undoManager.redo();
            return true;
        }

        return false;
    }

    /**
     * Clear all undo operations from the {@link UndoManager}.<br>
     * You should use this method after you modified the sequence without providing any <i>undo</i>
     * support.
     */
    public void clearUndoManager()
    {
        getUndoManager().discardAllEdits();
    }

    protected void setColorModel(IcyColorModel cm)
    {
        // remove listener
        if (colorModel != null)
            colorModel.removeListener(this);

        colorModel = cm;

        // add listener
        if (cm != null)
            cm.addListener(this);

        // sequence type changed
        typeChanged();
        // sequence component bounds changed
        componentBoundsChanged(cm, -1);
        // sequence colormap changed
        colormapChanged(cm, -1);
    }

    /**
     * @deprecated Use {@link SequenceUtil#convertToType(Sequence, DataType, boolean)} instead.
     */
    @Deprecated
    public Sequence convertToType(DataType dataType, boolean rescale)
    {
        return SequenceUtil.convertToType(this, dataType, rescale);
    }

    /**
     * @deprecated Use {@link SequenceUtil#convertType(Sequence, DataType, Scaler[])} instead.
     */
    @Deprecated
    public Sequence convertToType(DataType dataType, Scaler scaler)
    {
        return SequenceUtil.convertToType(this, dataType, scaler);
    }

    /**
     * @deprecated Use {@link SequenceUtil#convertToType(Sequence, DataType, boolean)} instead
     */
    @Deprecated
    public Sequence convertToType(int dataType, boolean signed, boolean rescale)
    {
        return convertToType(DataType.getDataType(dataType, signed), rescale);
    }

    /**
     * @deprecated Use {@link SequenceUtil#extractChannel(Sequence, int)} instead.
     */
    @Deprecated
    public Sequence extractChannel(int channelNumber)
    {
        return SequenceUtil.extractChannel(this, channelNumber);
    }

    /**
     * @deprecated Use {@link SequenceUtil#extractChannels(Sequence, List)} instead.
     */
    @Deprecated
    public Sequence extractChannels(List<Integer> channelNumbers)
    {
        return SequenceUtil.extractChannels(this, channelNumbers);
    }

    /**
     * @deprecated Use {@link SequenceUtil#extractChannel(Sequence, int)} instead
     */
    @Deprecated
    public Sequence extractBand(int bandNumber)
    {
        return extractChannel(bandNumber);
    }

    /**
     * @deprecated Use {@link SequenceUtil#extractChannels(Sequence, List)} instead
     */
    @Deprecated
    public Sequence extractBands(List<Integer> bandNumbers)
    {
        return extractChannels(bandNumbers);
    }

    /**
     * Returns all VolumetricImage as TreeMap (contains t position)
     */
    public TreeMap<Integer, VolumetricImage> getVolumetricImages()
    {
        synchronized (volumetricImages)
        {
            return new TreeMap<Integer, VolumetricImage>(volumetricImages);
        }
    }

    /**
     * Returns all VolumetricImage
     */
    public ArrayList<VolumetricImage> getAllVolumetricImage()
    {
        synchronized (volumetricImages)
        {
            return new ArrayList<VolumetricImage>(volumetricImages.values());
        }
    }

    /**
     * Returns first viewer attached to this sequence
     */
    public Viewer getFirstViewer()
    {
        return Icy.getMainInterface().getFirstViewer(this);
    }

    /**
     * Returns viewers attached to this sequence
     */
    public ArrayList<Viewer> getViewers()
    {
        return Icy.getMainInterface().getViewers(this);
    }

    /**
     * Set the volatile state for this Sequence (see {@link IcyBufferedImage#setVolatile(boolean)}).<br>
     * 
     * @throws OutOfMemoryError
     *         if there is not enough memory available to store image
     *         data when setting back to <i>non volatile</i> state
     * @throws UnsupportedOperationException
     *         if cache engine is not initialized (error at initialization).
     */
    public void setVolatile(boolean value) throws OutOfMemoryError, UnsupportedOperationException
    {
        final boolean vol = isVolatile();

        try
        {
            // change volatile state for all images
            for (IcyBufferedImage image : getAllImage())
                if (image != null)
                    image.setVolatile(value);

            if (vol != value)
                metaChanged(ID_VIRTUAL);
        }
        catch (OutOfMemoryError e)
        {
            // not enough memory to complete the operation --> restore previous state
            for (IcyBufferedImage image : getAllImage())
                if (image != null)
                    image.setVolatile(!value);

            throw e;
        }
    }

    /**
     * Same as {@link #setVolatile(boolean)}
     * 
     * @throws OutOfMemoryError
     *         if there is not enough memory available to store image
     *         data when setting back to <i>non volatile</i> state
     * @throws UnsupportedOperationException
     *         if cache engine is not initialized (error at initialization).
     */
    public void setVirtual(boolean value) throws OutOfMemoryError, UnsupportedOperationException
    {
        setVolatile(value);
    }

    /**
     * Returns true if this sequence contains volatile image (see {@link IcyBufferedImage#isVolatile()}).
     */
    public boolean isVolatile()
    {
        final IcyBufferedImage img = getFirstNonNullImage();
        if (img != null)
            return img.isVolatile();

        return false;
    }

    /**
     * Same as {@link #isVolatile()}
     */
    public boolean isVirtual()
    {
        return isVolatile();
    }

    /**
     * get sequence id (this id is unique during an ICY session)
     */
    public int getId()
    {
        return id;
    }

    /**
     * Sequence name
     */
    public void setName(String value)
    {
        if (getName() != value)
        {
            MetaDataUtil.setName(metaData, 0, value);
            metaChanged(ID_NAME);
        }
    }

    public String getName()
    {
        return MetaDataUtil.getName(metaData, 0);
    }

    /**
     * Returns the origin filename for the specified image position.<br>
     * This method is useful for sequence loaded from multiple files.
     * 
     * @return the origin filename for the given image position
     */
    public String getFilename(int t, int z, int c)
    {
        final ImageProvider importer = getImageProvider();

        // group importer ? we can retrieve the original filename
        if (importer instanceof SequenceFileGroupImporter)
            return ((SequenceFileGroupImporter) importer).getPath(z, t, c);

        return filename;
    }

    /**
     * Returns the origin filename (from/to which the sequence has been loaded/saved).<br>
     * This filename information is also used to store the XML persistent data.<br/>
     * null / empty --> no file attachment<br>
     * image file --> single file attachment
     * directory or metadata file --> multiples files attachment<br>
     * 
     * @return the origin filename
     */
    public String getFilename()
    {
        return filename;
    }

    /**
     * Set the origin filename (from/to which the sequence has been loaded/saved).<br>
     * When you set the filename you need to ensure that "sub part" information are correctly reset (setOriginXXX(...)
     * methods) as this filename will be used to generate the XML persistent data file name.<br/>
     * null / empty --> no file attachment<br>
     * image file --> single file attachment
     * directory or metadata file --> multiples files attachment<br>
     * 
     * @param filename
     *        the filename to set
     */
    public void setFilename(String filename)
    {
        if (this.filename != filename)
        {
            this.filename = filename;
        }
    }

    /**
     * Returns the {@link ImageProvider} used to load the sequence data.<br>
     * It can return <code>null</code> if the Sequence was not loaded from a specific resource or if it was saved in between.<br>
     * 
     * @return the {@link ImageProvider} used to load the Sequence
     */
    public ImageProvider getImageProvider()
    {
        return imageProvider;
    }

    /**
     * Set the {@link ImageProvider} used to load the sequence data.<br>
     * When you set the <i>ImageProvider</i> you need to ensure we can use it (should be opened for {@link SequenceIdImporter}).<br>
     * Also "sub part" informations has to be correctly set (setOriginXXX(...) methods) as we may use it to retrieve sequence data from the
     * {@link ImageProvider}.
     */
    public void setImageProvider(ImageProvider value)
    {
        try
        {
            // close previous
            if ((imageProvider != null) && (imageProvider instanceof Closeable))
                ((Closeable) imageProvider).close();
        }
        catch (IOException e)
        {
            // ignore
        }

        imageProvider = value;
    }

    /**
     * Returns the output base filename.<br>
     * This function is supposed to be used internally only.
     * 
     * @param folderExt
     *        If the filename of this sequence refer a folder then we extend it with 'folderExt' to build the base name.
     * @see #getOutputExtension()
     */
    public String getOutputBaseName(String folderExt)
    {
        String result = getFilename();

        if (StringUtil.isEmpty(result))
            return "";

        // remove some problematic character for XML file
        result = FileUtil.cleanPath(result);

        // filename reference a directory --> use "<directory>/<folderExt>"
        if (FileUtil.isDirectory(result))
            result += "/" + folderExt;
        // otherwise remove extension
        else
            result = FileUtil.setExtension(result, "");

        return result;
    }

    /**
     * Returns the output filename extension (not the file extension, just extension from base name).<br>
     * The extension is based on some internals informations as serie index and resolution level.<br>
     * This function is supposed to be used internally only.
     * 
     * @see #getOutputBaseName(String)
     */
    public String getOutputExtension()
    {
        String result = "";

        // retrieve the serie index
        final int serieNum = getSeries();

        // multi serie image --> add a specific extension
        if (serieNum != 0)
            result += "_S" + serieNum;

        // retrieve the resolution
        final int resolution = getOriginResolution();

        // sub resolution --> add a specific extension
        if (resolution != 0)
            result += "_R" + resolution;

        // retrieve the XY region offset
        final Rectangle xyRegion = getOriginXYRegion();

        // not null --> add a specific extension
        if (xyRegion != null)
            result += "_XY(" + xyRegion.x + "," + xyRegion.y + "-" + xyRegion.width + "," + xyRegion.height + ")";

        // retrieve the Z range
        final int zMin = getOriginZMin();
        final int zMax = getOriginZMax();

        // sub Z range --> add a specific extension
        if ((zMin != -1) || (zMax != -1))
        {
            if (zMin == zMax)
                result += "_Z" + zMin;
            else
                result += "_Z(" + zMin + "-" + zMax + ")";
        }

        // retrieve the T range
        final int tMin = getOriginTMin();
        final int tMax = getOriginTMax();

        // sub T range --> add a specific extension
        if ((tMin != -1) || (tMax != -1))
        {
            if (tMin == tMax)
                result += "_T" + tMin;
            else
                result += "_T(" + tMin + "-" + tMax + ")";
        }

        // retrieve the original channel
        final int channel = getOriginChannel();

        // single channel extraction --> add a specific extension
        if (channel != -1)
            result += "_C" + channel;

        return result;
    }

    /**
     * Return the desired output filename for this Sequence.<br>
     * It uses the origin filename and add a specific extension depending some internals properties.
     * 
     * @param withExtension
     *        Add the original file extension is set to <code>true</code>
     * @see #getFilename()
     * @see #getOutputBaseName(String)
     * @see #getOutputExtension()
     */
    public String getOutputFilename(boolean withExtension)
    {
        String result = getFilename();

        if (StringUtil.isEmpty(result))
            return "";

        final String ext = FileUtil.getFileExtension(result, true);

        result = getOutputBaseName(FileUtil.getFileName(result, false)) + getOutputExtension();
        if (withExtension)
            result += ext;

        return result;
    }

    /**
     * Returns the resolution level from the origin image (defined by {@link #getFilename()}).<br>
     * By default it returns 0 if this sequence corresponds to the full resolution of the original image.<br>
     * 1 --> original resolution / 2<br>
     * 2 --> original resolution / 4<br>
     * 3 --> original resolution / 8<br>
     * ...
     */
    public int getOriginResolution()
    {
        return originResolution;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginResolution()
     */
    public void setOriginResolution(int value)
    {
        originResolution = value;
    }

    /**
     * Returns the region (X,Y) from original image if this image is a crop of the original image (in original image
     * resolution).<br>
     * Default value is <code>null</code> (full size).
     */
    public Rectangle getOriginXYRegion()
    {
        return originXYRegion;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginXYRegion()
     */
    public void setOriginXYRegion(Rectangle value)
    {
        // better to use a copy
        if (value != null)
            originXYRegion = new Rectangle(value);
        // clear it
        else
            originXYRegion = null;
    }

    /**
     * Returns the Z range minimum from original image if this image is a crop in Z of the original image.<br>
     * Default value is -1 which mean we have the whole Z range.
     */
    public int getOriginZMin()
    {
        return originZMin;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginZMin()
     */
    public void setOriginZMin(int value)
    {
        originZMin = value;
    }

    /**
     * Returns the Z range maximum from original image if this image is a crop in Z of the original image.<br>
     * Default value is -1 which mean we have the whole Z range.
     */
    public int getOriginZMax()
    {
        return originZMax;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginZMax()
     */
    public void setOriginZMax(int value)
    {
        originZMax = value;
    }

    /**
     * Returns the T range minimum from original image if this image is a crop in T of the original image.<br>
     * Default value is -1 which mean we have the whole T range.
     */
    public int getOriginTMin()
    {
        return originTMin;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginTMin()
     */
    public void setOriginTMin(int value)
    {
        originTMin = value;
    }

    /**
     * Returns the T range maximum from original image if this image is a crop in T of the original image.<br>
     * Default value is -1 which mean we have the whole T range.
     */
    public int getOriginTMax()
    {
        return originTMax;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginTMax()
     */
    public void setOriginTMax(int value)
    {
        originTMax = value;
    }

    /**
     * Returns the channel position from original image if this image is a single channel extraction of the original
     * image.<br>
     * Default value is -1 which mean that all channels were preserved.
     */
    public int getOriginChannel()
    {
        return originChannel;
    }

    /**
     * Internal use only, you should not directly use this method.
     * 
     * @see #getOriginChannel()
     */
    public void setOriginChannel(int value)
    {
        originChannel = value;
    }

    /**
     * Reset origin information (used after saved operation normally, internal use only).
     */
    public void resetOriginInformation()
    {
        setSeries(0);
        setOriginChannel(-1);
        setOriginResolution(0);
        setOriginTMin(-1);
        setOriginTMax(-1);
        setOriginZMin(-1);
        setOriginZMax(-1);
        setOriginXYRegion(null);
    }

    /**
     * Returns series index if the Sequence comes from a multi serie image.<br>
     * By default it returns 0 if the sequence comes from a single serie image or if this is the
     * first series image.
     */
    public int getSeries()
    {
        // retrieve the image ID (sequences are always single serie)
        final String id = MetaDataUtil.getImageID(getOMEXMLMetadata(), 0);

        if (id.startsWith("Image:"))
        {
            final String[] serieNums = id.substring(6).split(":");

            if (serieNums.length > 0)
                return StringUtil.parseInt(serieNums[0], 0);
        }

        return 0;
    }

    /**
     * Set series index if the Sequence comes from a multi serie image (internal use only).
     */
    public void setSeries(int value)
    {
        // retrieve the image ID (sequences are always single serie)
        final String id = MetaDataUtil.getImageID(getOMEXMLMetadata(), 0);

        if (id.startsWith("Image:"))
            MetaDataUtil.setImageID(getOMEXMLMetadata(), 0, "Image:" + value);
    }

    /**
     * @deprecated Use {@link #getSeries()} instead
     */
    @Deprecated
    public int getSerieIndex()
    {
        return getSeries();
    }

    /**
     * Returns meta data object
     */
    public OMEXMLMetadata getOMEXMLMetadata()
    {
        return metaData;
    }

    /**
     * Set the meta data object
     */
    public void setMetaData(OMEXMLMetadata metaData)
    {
        if (this.metaData != metaData)
        {
            this.metaData = metaData;
            // all meta data changed
            metaChanged(null);
        }
    }

    /**
     * @deprecated Use {@link #getOMEXMLMetadata()} instead.
     */
    @Deprecated
    public OMEXMLMetadataImpl getMetadata()
    {
        return (OMEXMLMetadataImpl) getOMEXMLMetadata();
    }

    /**
     * @deprecated Use {@link #setMetaData(OMEXMLMetadata)} instead.
     */
    @Deprecated
    public void setMetaData(OMEXMLMetadataImpl metaData)
    {
        setMetaData((OMEXMLMetadata) metaData);
    }

    /**
     * Returns the physical position [X,Y,Z] (in �m) of the image represented by this Sequence.
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image from the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we just use value from Plane(0,0,0) then we use the pixels size and time interval
     * information to compute other positions.
     */
    public double[] getPosition()
    {
        return new double[] {getPositionX(), getPositionY(), getPositionZ()};
    }

    /**
     * Returns the X physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we just use value from Plane(0,0,0) then we use the pixels size and time interval
     * information to compute other positions.
     */
    public double getPositionX()
    {
        return MetaDataUtil.getPositionX(metaData, 0, 0, 0, 0, 0d);
    }

    /**
     * Returns the Y physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we just use value from Plane(0,0,0) then we use the pixels size and time interval
     * information to compute other positions.
     */
    public double getPositionY()
    {
        return MetaDataUtil.getPositionY(metaData, 0, 0, 0, 0, 0d);
    }

    /**
     * Returns the Z physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we just use value from Plane(0,0,0) then we use the pixels size and time interval
     * information to compute other positions.
     */
    public double getPositionZ()
    {
        return MetaDataUtil.getPositionZ(metaData, 0, 0, 0, 0, 0d);
    }

    /**
     * Same as {@link #getTimeStamp()}
     */
    public long getPositionT()
    {
        return getTimeStamp();
    }

    /**
     * Returns the timestamp (elapsed milliseconds from the Java epoch of 1970-01-01 T00:00:00Z) of the image represented by this Sequence.
     *
     * @see #getPositionTOffset(int, int, int)
     * @see #getTimeInterval()
     */
    public long getTimeStamp()
    {
        return MetaDataUtil.getTimeStamp(metaData, 0, 0L);
    }

    /**
     * Returns the time position offset (in second for OME compatibility) relative to first image for the image at specified (T,Z,C) position.
     * 
     * @see #getTimeInterval()
     * @see #getTimeStamp()
     */
    public double getPositionTOffset(int t, int z, int c)
    {
        return MetaDataUtil.getPositionTOffset(metaData, 0, t, z, c, 0d);
    }

    /**
     * Sets the X physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we always use value from Plane(0,0,0)
     */
    public void setPositionX(double value)
    {
        if (getPositionX() != value)
        {
            MetaDataUtil.setPositionX(metaData, 0, 0, 0, 0, value);
            metaChanged(ID_POSITION_X);
        }
    }

    /**
     * Sets the X physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we always use value from Plane(0,0,0)
     */
    public void setPositionY(double value)
    {
        if (getPositionY() != value)
        {
            MetaDataUtil.setPositionY(metaData, 0, 0, 0, 0, value);
            metaChanged(ID_POSITION_Y);
        }
    }

    /**
     * Sets the X physical position / offset (in �m) of the image represented by this Sequence.<br>
     * This information can be used to represent the position of the image in the original sample (microscope
     * information) or the position of a sub image the original image (crop operation).<br>
     * Note that OME store this information at Plane level (each Z,T,C), here we always use value from Plane(0,0,0)
     */
    public void setPositionZ(double value)
    {
        if (getPositionZ() != value)
        {
            MetaDataUtil.setPositionZ(metaData, 0, 0, 0, 0, value);
            metaChanged(ID_POSITION_Z);
        }
    }

    /**
     * Same as {@link #setTimeStamp(long)}
     */
    public void setPositionT(long value)
    {
        setTimeStamp(value);
    }

    /**
     * Sets the timestamp (elapsed milliseconds from the Java epoch of 1970-01-01 T00:00:00Z) for the image represented by this Sequence.
     * 
     * @see #setPositionTOffset(int, int, int, double)
     * @see #setTimeInterval(double)
     */
    public void setTimeStamp(long value)
    {
        if (getTimeStamp() != value)
        {
            MetaDataUtil.setTimeStamp(metaData, 0, value);
            metaChanged(ID_POSITION_T);
        }
    }

    /**
     * Sets the time position / offset (in second for OME compatibility) relative to first image for the image at specified (T,Z,C) position.
     * 
     * @see #setTimeInterval(double)
     * @see #setTimeStamp(long)
     */
    public void setPositionTOffset(int t, int z, int c, double value)
    {
        if (getPositionTOffset(t, z, c) != value)
        {
            MetaDataUtil.setPositionTOffset(metaData, 0, t, z, c, value);
            metaChanged(ID_POSITION_T_OFFSET, t);
        }
    }

    /**
     * Returns pixel size for [X,Y,Z] dimension (in �m to be OME compatible)
     */
    public double[] getPixelSize()
    {
        return new double[] {getPixelSizeX(), getPixelSizeY(), getPixelSizeZ()};
    }

    /**
     * Returns X pixel size (in �m to be OME compatible)
     */
    public double getPixelSizeX()
    {
        return MetaDataUtil.getPixelSizeX(metaData, 0, 1d);
    }

    /**
     * Returns Y pixel size (in �m to be OME compatible)
     */
    public double getPixelSizeY()
    {
        return MetaDataUtil.getPixelSizeY(metaData, 0, 1d);
    }

    /**
     * Returns Z pixel size (in �m to be OME compatible)
     */
    public double getPixelSizeZ()
    {
        return MetaDataUtil.getPixelSizeZ(metaData, 0, 1d);
    }

    /**
     * Returns T time interval (in second for OME compatibility)
     * 
     * @see #getPositionTOffset(int, int, int)
     */
    public double getTimeInterval()
    {
        double result = MetaDataUtil.getTimeInterval(metaData, 0, 0d);

        // not yet defined ?
        if (result == 0d)
        {
            result = MetaDataUtil.getTimeIntervalFromTimePositions(metaData, 0);
            // we got something --> set it as the time interval
            if (result != 0d)
                MetaDataUtil.setTimeInterval(metaData, 0, result);
        }

        return result;
    }

    /**
     * Set X pixel size (in �m to be OME compatible)
     */
    public void setPixelSizeX(double value)
    {
        if (getPixelSizeX() != value)
        {
            MetaDataUtil.setPixelSizeX(metaData, 0, value);
            metaChanged(ID_PIXEL_SIZE_X);
        }
    }

    /**
     * Set Y pixel size (in �m to be OME compatible)
     */
    public void setPixelSizeY(double value)
    {
        if (getPixelSizeY() != value)
        {
            MetaDataUtil.setPixelSizeY(metaData, 0, value);
            metaChanged(ID_PIXEL_SIZE_Y);
        }
    }

    /**
     * Set Z pixel size (in �m to be OME compatible)
     */
    public void setPixelSizeZ(double value)
    {
        if (getPixelSizeZ() != value)
        {
            MetaDataUtil.setPixelSizeZ(metaData, 0, value);
            metaChanged(ID_PIXEL_SIZE_Z);
        }
    }

    /**
     * Set T time resolution (in second to be OME compatible)
     * 
     * @see #setPositionTOffset(int, int, int, double)
     */
    public void setTimeInterval(double value)
    {
        if (MetaDataUtil.getTimeInterval(metaData, 0, 0d) != value)
        {
            MetaDataUtil.setTimeInterval(metaData, 0, value);
            metaChanged(ID_TIME_INTERVAL);
        }
    }

    /**
     * Returns the pixel size scaling factor to convert a number of pixel/voxel unit into <code>�m</code><br/>
     * <br>
     * For instance to get the scale ration for 2D distance:<br>
     * <code>valueMicroMeter = pixelNum * getPixelSizeScaling(2, 1)</code><br>
     * For a 2D surface:<br>
     * <code>valueMicroMeter2 = pixelNum * getPixelSizeScaling(2, 2)</code><br>
     * For a 3D volume:<br>
     * <code>valueMicroMeter3 = pixelNum * getPixelSizeScaling(3, 3)</code><br>
     * 
     * @param dimCompute
     *        dimension order for size calculation<br>
     *        <li>1 --> pixel size X used for conversion</li><br>
     *        <li>2 --> pixel size X and Y used for conversion</li><br>
     *        <li>3 or above --> pixel size X, Y and Z used for conversion</li><br>
     * @param dimResult
     *        dimension order for the result (unit)<br>
     *        <li>1 --> distance</li><br>
     *        <li>2 --> area</li><br>
     *        <li>3 or above --> volume</li><br>
     */
    public double getPixelSizeScaling(int dimCompute, int dimResult)
    {
        double result;

        switch (dimCompute)
        {
            case 0:
                // incorrect
                return 0d;

            case 1:
                result = getPixelSizeX();
                break;

            case 2:
                result = getPixelSizeX() * getPixelSizeY();
                break;

            default:
                result = getPixelSizeX() * getPixelSizeY() * getPixelSizeZ();
                break;
        }

        result = Math.pow(result, (double) dimResult / (double) dimCompute);

        return result;
    }

    /**
     * Returns the best pixel size unit for the specified dimension order given the sequence's pixel
     * size informations.<br/>
     * <li>Compute a 2D distance:</li>
     * 
     * <pre>
     * dimCompute = 2;
     * dimUnit = 1;
     * valueMicroMeter = pixelNum * getPixelSizeScaling(dimCompute);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * finalValue = UnitUtil.getValueInUnit(valueMicroMeter, UnitPrefix.MICRO, bestUnit);
     * valueString = Double.toString(finalValue) + &quot; &quot; + bestUnit.toString() + &quot;m&quot;;
     * </pre>
     * 
     * <li>Compute a 2D surface:</li>
     * 
     * <pre>
     * dimCompute = 2;
     * dimUnit = 2;
     * valueMicroMeter = pixelNum * getPixelSizeScaling(dimCompute);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * finalValue = UnitUtil.getValueInUnit(valueMicroMeter, UnitPrefix.MICRO, bestUnit);
     * valueString = Double.toString(finalValue) + &quot; &quot; + bestUnit.toString() + &quot;m2&quot;;
     * </pre>
     * 
     * <li>Compute a 3D volume:</li>
     * 
     * <pre>
     * dimCompute = 3;
     * dimUnit = 3;
     * valueMicroMeter = pixelNum * getPixelSizeScaling(dimCompute);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * finalValue = UnitUtil.getValueInUnit(valueMicroMeter, UnitPrefix.MICRO, bestUnit);
     * valueString = Double.toString(finalValue) + &quot; &quot; + bestUnit.toString() + &quot;m3&quot;;
     * </pre>
     * 
     * @param dimCompute
     *        dimension order for size calculation<br>
     *        <li>1 --> pixel size X used for conversion</li><br>
     *        <li>2 --> pixel size X and Y used for conversion</li><br>
     *        <li>3 or above --> pixel size X, Y and Z used for conversion</li><br>
     * @param dimResult
     *        dimension order for the result (unit)<br>
     *        <li>1 --> distance</li><br>
     *        <li>2 --> area</li><br>
     *        <li>3 or above --> volume</li><br>
     * @see #calculateSizeBestUnit(double, int, int)
     */
    public UnitPrefix getBestPixelSizeUnit(int dimCompute, int dimResult)
    {
        switch (dimResult)
        {
            case 0:
                // keep original
                return UnitPrefix.MICRO;

            case 1:
                return UnitUtil.getBestUnit((getPixelSizeScaling(dimCompute, dimResult) * 10), UnitPrefix.MICRO,
                        dimResult);

            case 2:
                return UnitUtil.getBestUnit((getPixelSizeScaling(dimCompute, dimResult) * 100), UnitPrefix.MICRO,
                        dimResult);

            default:
                return UnitUtil.getBestUnit((getPixelSizeScaling(dimCompute, dimResult) * 1000), UnitPrefix.MICRO,
                        dimResult);
        }
    }

    /**
     * Returns the size in �m for the specified amount of sample/pixel value in the specified
     * dimension order.<br>
     * <br>
     * For the perimeter in �m:<br>
     * <code>perimeter = calculateSize(contourInPixel, 2, 1)</code><br>
     * For a 2D surface in �m2:<br>
     * <code>surface = calculateSize(interiorInPixel, 2, 2)</code><br>
     * For a 2D surface area in �m2:<br>
     * <code>volume = calculateSize(contourInPixel, 3, 2)</code><br>
     * For a 3D volume in �m3:<br>
     * <code>volume = calculateSize(interiorInPixel, 3, 3)</code><br>
     * 
     * @param pixelNumber
     *        number of pixel
     * @param dimCompute
     *        dimension order for size calculation<br>
     *        <li>1 --> pixel size X used for conversion</li><br>
     *        <li>2 --> pixel size X and Y used for conversion</li><br>
     *        <li>3 or above --> pixel size X, Y and Z used for conversion</li><br>
     * @param dimResult
     *        dimension order for the result (unit)<br>
     *        <li>1 --> distance</li><br>
     *        <li>2 --> area</li><br>
     *        <li>3 or above --> volume</li><br>
     * @see #calculateSizeBestUnit(double, int, int)
     */
    public double calculateSize(double pixelNumber, int dimCompute, int dimResult)
    {
        return pixelNumber * getPixelSizeScaling(dimCompute, dimResult);
    }

    /**
     * Returns the size converted in the best unit (see {@link #getBestPixelSizeUnit(int, int)} for
     * the specified amount of sample/pixel value in the specified dimension order.<br/>
     * <li>Compute a 2D distance:</li>
     * 
     * <pre>
     * dimCompute = 2;
     * dimUnit = 1;
     * valueBestUnit = calculateSizeBestUnit(pixelNum, dimCompute, dimUnit);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * valueString = Double.toString(valueBestUnit) + &quot; &quot; + bestUnit.toString() + &quot;m&quot;;
     * </pre>
     * 
     * <li>Compute a 2D surface:</li>
     * 
     * <pre>
     * dimCompute = 2;
     * dimUnit = 2;
     * valueBestUnit = calculateSizeBestUnit(pixelNum, dimCompute, dimUnit);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * valueString = Double.toString(valueBestUnit) + &quot; &quot; + bestUnit.toString() + &quot;m2&quot;;
     * </pre>
     * 
     * <li>Compute a 3D volume:</li>
     * 
     * <pre>
     * dimCompute = 3;
     * dimUnit = 3;
     * valueBestUnit = calculateSizeBestUnit(pixelNum, dimCompute, dimUnit);
     * bestUnit = getBestPixelSizeUnit(dimCompute, dimUnit);
     * valueString = Double.toString(valueBestUnit) + &quot; &quot; + bestUnit.toString() + &quot;m3&quot;;
     * </pre>
     * 
     * @param pixelNumber
     *        number of pixel
     * @param dimCompute
     *        dimension order for size calculation<br>
     *        <li>1 --> pixel size X used for conversion</li><br>
     *        <li>2 --> pixel size X and Y used for conversion</li><br>
     *        <li>3 or above --> pixel size X, Y and Z used for conversion</li><br>
     * @param dimResult
     *        dimension order for the result (unit)<br>
     *        <li>1 --> distance</li><br>
     *        <li>2 --> area</li><br>
     *        <li>3 or above --> volume</li><br>
     * @see #calculateSize(double, int, int)
     * @see #getBestPixelSizeUnit(int, int)
     */
    public double calculateSizeBestUnit(double pixelNumber, int dimCompute, int dimResult)
    {
        final double value = calculateSize(pixelNumber, dimCompute, dimResult);
        final UnitPrefix unit = getBestPixelSizeUnit(dimCompute, dimResult);
        return UnitUtil.getValueInUnit(value, UnitPrefix.MICRO, unit, dimResult);
    }

    /**
     * Returns the size and appropriate unit in form of String for specified amount of sample/pixel
     * value in the specified dimension order.<br>
     * <br>
     * For instance if you want to retrieve the 2D distance:<br>
     * <code>distanceStr = calculateSize(distanceInPixel, 2, 1, 5)</code><br>
     * For a 2D surface:<br>
     * <code>surfaceStr = calculateSize(surfaceInPixel, 2, 2, 5)</code><br>
     * For a 3D volume:<br>
     * <code>volumeStr = calculateSize(volumeInPixel, 3, 3, 5)</code><br>
     * 
     * @param pixelNumber
     *        number of pixel
     * @param dimCompute
     *        dimension order for the calculation
     * @param dimResult
     *        dimension order for the result (unit)
     * @param significantDigit
     *        wanted significant digit for the result (0 for all)
     * @see #calculateSize(double, int, int)
     */
    public String calculateSize(double pixelNumber, int dimCompute, int dimResult, int significantDigit)
    {
        double value = calculateSize(pixelNumber, dimCompute, dimResult);
        final String postFix = (dimResult > 1) ? StringUtil.toString(dimResult) : "";
        final UnitPrefix unit = UnitUtil.getBestUnit(value, UnitPrefix.MICRO, dimResult);
        // final UnitPrefix unit = getBestPixelSizeUnit(dimCompute, dimResult);

        value = UnitUtil.getValueInUnit(value, UnitPrefix.MICRO, unit, dimResult);
        if (significantDigit != 0)
            value = MathUtil.roundSignificant(value, significantDigit);

        return StringUtil.toString(value) + " " + unit.toString() + "m" + postFix;
    }

    /**
     * Get default name for specified channel
     */
    public String getDefaultChannelName(int index)
    {
        return MetaDataUtil.getDefaultChannelName(index);
    }

    /**
     * Get name for specified channel
     */
    public String getChannelName(int index)
    {
        return MetaDataUtil.getChannelName(metaData, 0, index);
    }

    /**
     * Set name for specified channel
     */
    public void setChannelName(int index, String value)
    {
        if (!StringUtil.equals(getChannelName(index), value))
        {
            MetaDataUtil.setChannelName(metaData, 0, index, value);
            metaChanged(ID_CHANNEL_NAME, index);
        }
    }

    /**
     * @deprecated Use {@link #getAutoUpdateChannelBounds()} instead.
     */
    @Deprecated
    public boolean isComponentAbsBoundsAutoUpdate()
    {
        return getAutoUpdateChannelBounds();
    }

    /**
     * @deprecated Use {@link #setAutoUpdateChannelBounds(boolean)} instead.
     */
    @Deprecated
    public void setComponentAbsBoundsAutoUpdate(boolean value)
    {
        // nothing here
    }

    /**
     * @return true is channel bounds are automatically updated when sequence data is modified.
     * @see #setAutoUpdateChannelBounds(boolean)
     */
    public boolean getAutoUpdateChannelBounds()
    {
        return autoUpdateChannelBounds;
    }

    /**
     * If set to <code>true</code> (default) then channel bounds will be automatically recalculated
     * when sequence data is modified.<br>
     * This can consume a lot of time if you make many updates on large sequence.<br>
     * In this case you should do your updates in a {@link #beginUpdate()} ... {@link #endUpdate()} block to avoid
     * severals recalculation.
     */
    public void setAutoUpdateChannelBounds(boolean value)
    {
        if (autoUpdateChannelBounds != value)
        {
            if (value)
                updateChannelsBounds(false);

            autoUpdateChannelBounds = value;
        }
    }

    /**
     * @deprecated Use {@link #getAutoUpdateChannelBounds()} instead.
     */
    @Deprecated
    public boolean isComponentUserBoundsAutoUpdate()
    {
        return getAutoUpdateChannelBounds();
    }

    /**
     * @deprecated Use {@link #setAutoUpdateChannelBounds(boolean)} instead.
     */
    @Deprecated
    public void setComponentUserBoundsAutoUpdate(boolean value)
    {
        setAutoUpdateChannelBounds(value);
    }

    /**
     * @return the AWT dispatching property
     * @deprecated Don't use it, events should stay on current thread
     */
    @Deprecated
    public boolean isAWTDispatching()
    {
        return updater.isAwtDispatch();
    }

    /**
     * All events are dispatched on AWT when true else they are dispatched on current thread
     * 
     * @deprecated Don't use it, events should stay on current thread
     */
    @Deprecated
    public void setAWTDispatching(boolean value)
    {
        updater.setAwtDispatch(value);
    }

    /**
     * Add the specified listener to listeners list
     */
    public void addListener(SequenceListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Remove the specified listener from listeners list
     */
    public void removeListener(SequenceListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * Get listeners list
     */
    public SequenceListener[] getListeners()
    {
        return listeners.toArray(new SequenceListener[0]);
    }

    /**
     * Add the specified {@link icy.sequence.SequenceModel.SequenceModelListener} to listeners list
     */
    @Override
    public void addSequenceModelListener(SequenceModelListener listener)
    {
        modelListeners.add(listener);
    }

    /**
     * Remove the specified {@link icy.sequence.SequenceModel.SequenceModelListener} from listeners
     * list
     */
    @Override
    public void removeSequenceModelListener(SequenceModelListener listener)
    {
        modelListeners.remove(listener);
    }

    /**
     * Get the Undo manager of this sequence
     */
    public IcyUndoManager getUndoManager()
    {
        return undoManager;
    }

    /**
     * @deprecated Use {@link #contains(Overlay)} instead.
     */
    @Deprecated
    public boolean contains(Painter painter)
    {
        return getOverlay(painter) != null;
    }

    /**
     * Returns true if the sequence contains the specified overlay
     */
    public boolean contains(Overlay overlay)
    {
        if (overlay == null)
            return false;

        synchronized (overlays)
        {
            return overlays.contains(overlay);
        }
    }

    /**
     * Returns true if the sequence contains the specified ROI
     */
    public boolean contains(ROI roi)
    {
        if (roi == null)
            return false;

        synchronized (rois)
        {
            return rois.contains(roi);
        }
    }

    /**
     * @deprecated Use {@link #hasOverlay()} instead.
     */
    @Deprecated
    public boolean hasPainter()
    {
        return hasOverlay();
    }

    /**
     * @deprecated Use {@link #getOverlays()} instead.
     */
    @Deprecated
    public ArrayList<Painter> getPainters()
    {
        final ArrayList<Painter> result = new ArrayList<Painter>(overlays.size());

        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
            {
                if (overlay instanceof OverlayWrapper)
                    result.add(((OverlayWrapper) overlay).getPainter());
                else
                    result.add(overlay);
            }
        }

        return result;
    }

    /**
     * @deprecated Use {@link #getOverlaySet()} instead.
     */
    @Deprecated
    public HashSet<Painter> getPainterSet()
    {
        final HashSet<Painter> result = new HashSet<Painter>(overlays.size());

        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
            {
                if (overlay instanceof OverlayWrapper)
                    result.add(((OverlayWrapper) overlay).getPainter());
                else
                    result.add(overlay);
            }
        }

        return result;
    }

    /**
     * @deprecated Use {@link #getOverlays(Class)} instead.
     */
    @Deprecated
    public List<Painter> getPainters(Class<? extends Painter> painterClass)
    {
        final ArrayList<Painter> result = new ArrayList<Painter>(overlays.size());

        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
            {
                if (overlay instanceof OverlayWrapper)
                {
                    if (painterClass.isInstance(((OverlayWrapper) overlay).getPainter()))
                        result.add(overlay);
                }
                else
                {
                    if (painterClass.isInstance(overlay))
                        result.add(overlay);
                }
            }
        }

        return result;
    }

    /**
     * Returns true if the sequence contains at least one Overlay.
     */
    public boolean hasOverlay()
    {
        return overlays.size() > 0;
    }

    /**
     * Returns all overlays attached to this sequence
     */
    public List<Overlay> getOverlays()
    {
        synchronized (overlays)
        {
            return new ArrayList<Overlay>(overlays);
        }
    }

    /**
     * Returns all overlays attached to this sequence (HashSet form)
     */
    public Set<Overlay> getOverlaySet()
    {
        synchronized (overlays)
        {
            return new HashSet<Overlay>(overlays);
        }
    }

    /**
     * Returns true if the sequence contains Overlay of specified Overlay class.
     */
    public boolean hasOverlay(Class<? extends Overlay> overlayClass)
    {
        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
                if (overlayClass.isInstance(overlay))
                    return true;
        }

        return false;
    }

    /**
     * Returns overlays of specified class attached to this sequence
     */
    @SuppressWarnings("unchecked")
    public <T extends Overlay> List<T> getOverlays(Class<T> overlayClass)
    {
        final List<T> result = new ArrayList<T>(overlays.size());

        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
                if (overlayClass.isInstance(overlay))
                    result.add((T) overlay);
        }

        return result;
    }

    /**
     * Returns true if the sequence contains at least one ROI.
     */
    public boolean hasROI()
    {
        return rois.size() > 0;
    }

    /**
     * Returns all ROIs attached to this sequence.
     * 
     * @param sorted
     *        If true the returned list is ordered by the ROI id (creation order).
     */
    public List<ROI> getROIs(boolean sorted)
    {
        final List<ROI> result;

        synchronized (rois)
        {
            result = new ArrayList<ROI>(rois);
        }

        // sort it if required
        if (sorted)
            Collections.sort(result, ROI.idComparator);

        return result;
    }

    /**
     * Returns all ROIs attached to this sequence.
     */
    public ArrayList<ROI> getROIs()
    {
        return (ArrayList<ROI>) getROIs(false);
    }

    /**
     * Returns all ROIs attached to this sequence (HashSet form)
     */
    public HashSet<ROI> getROISet()
    {
        synchronized (rois)
        {
            return new HashSet<ROI>(rois);
        }
    }

    /**
     * Returns all 2D ROIs attached to this sequence.
     * 
     * @param sorted
     *        If true the returned list is ordered by the ROI id (creation order).
     */
    public List<ROI2D> getROI2Ds(boolean sorted)
    {
        final List<ROI2D> result = new ArrayList<ROI2D>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi instanceof ROI2D)
                    result.add((ROI2D) roi);
        }

        // sort it if required
        if (sorted)
            Collections.sort(result, ROI.idComparator);

        return result;
    }

    /**
     * Returns all 2D ROIs attached to this sequence.
     */
    public ArrayList<ROI2D> getROI2Ds()
    {
        return (ArrayList<ROI2D>) getROI2Ds(false);
    }

    /**
     * Returns all 3D ROIs attached to this sequence.
     * 
     * @param sorted
     *        If true the returned list is ordered by the ROI id (creation order).
     */
    public List<ROI3D> getROI3Ds(boolean sorted)
    {
        final List<ROI3D> result = new ArrayList<ROI3D>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi instanceof ROI3D)
                    result.add((ROI3D) roi);
        }

        // sort it if required
        if (sorted)
            Collections.sort(result, ROI.idComparator);

        return result;
    }

    /**
     * Returns all 3D ROIs attached to this sequence.
     */
    public ArrayList<ROI3D> getROI3Ds()
    {
        return (ArrayList<ROI3D>) getROI3Ds(false);
    }

    /**
     * Returns true if the sequence contains ROI of specified ROI class.
     */
    public boolean hasROI(Class<? extends ROI> roiClass)
    {
        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roiClass.isInstance(roi))
                    return true;
        }

        return false;
    }

    /**
     * Returns ROIs of specified class attached to this sequence
     */
    @SuppressWarnings("unchecked")
    public <T extends ROI> List<T> getROIs(Class<T> roiClass, boolean sorted)
    {
        final List<T> result = new ArrayList<T>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roiClass.isInstance(roi))
                    result.add((T) roi);
        }

        // sort it if required
        if (sorted)
            Collections.sort(result, ROI.idComparator);

        return result;
    }

    /**
     * @deprecated Use {@link #getROIs(Class, boolean)} instead
     */
    @Deprecated
    public List<ROI> getROIs(Class<? extends ROI> roiClass)
    {
        final List<ROI> result = new ArrayList<ROI>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roiClass.isInstance(roi))
                    result.add(roi);
        }

        return result;
    }

    /**
     * Returns the number of ROI of specified ROI class attached to the sequence.
     */
    public int getROICount(Class<? extends ROI> roiClass)
    {
        int result = 0;

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roiClass.isInstance(roi))
                    result++;
        }

        return result;
    }

    /**
     * Returns true if the sequence contains at least one selected ROI.
     */
    public boolean hasSelectedROI()
    {
        return getSelectedROI() != null;
    }

    /**
     * Returns the first selected ROI found (null if no ROI selected)
     */
    public ROI getSelectedROI()
    {
        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected())
                    return roi;
        }

        return null;
    }

    /**
     * Returns the first selected 2D ROI found (null if no 2D ROI selected)
     */
    public ROI2D getSelectedROI2D()
    {
        synchronized (rois)
        {
            for (ROI roi : rois)
                if ((roi instanceof ROI2D) && roi.isSelected())
                    return (ROI2D) roi;
        }

        return null;
    }

    /**
     * Returns the first selected 3D ROI found (null if no 3D ROI selected)
     */
    public ROI3D getSelectedROI3D()
    {
        synchronized (rois)
        {
            for (ROI roi : rois)
                if ((roi instanceof ROI3D) && roi.isSelected())
                    return (ROI3D) roi;
        }

        return null;
    }

    /**
     * Returns all selected ROI of given class (Set format).
     * 
     * @param roiClass
     *        ROI class restriction
     * @param wantReadOnly
     *        also return ROI with read only state
     */
    public Set<ROI> getSelectedROISet(Class<? extends ROI> roiClass, boolean wantReadOnly)
    {
        final Set<ROI> result = new HashSet<ROI>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected() && roiClass.isInstance(roi))
                    if (wantReadOnly || !roi.isReadOnly())
                        result.add(roi);
        }

        return result;
    }

    /**
     * Returns all selected ROI of given class (Set format).
     * 
     * @param roiClass
     *        ROI class restriction
     */
    @SuppressWarnings("unchecked")
    public <T extends ROI> Set<T> getSelectedROISet(Class<T> roiClass)
    {
        final Set<T> result = new HashSet<T>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected() && roiClass.isInstance(roi))
                    result.add((T) roi);
        }

        return result;
    }

    /**
     * Returns all selected ROI (Set format).
     */
    public Set<ROI> getSelectedROISet()
    {
        final Set<ROI> result = new HashSet<ROI>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected())
                    result.add(roi);
        }

        return result;
    }

    /**
     * Returns all selected ROI of given class.
     * 
     * @param roiClass
     *        ROI class restriction
     * @param sorted
     *        If true the returned list is ordered by the ROI id (creation order)
     * @param wantReadOnly
     *        also return ROI with read only state
     */
    @SuppressWarnings("unchecked")
    public <T extends ROI> List<T> getSelectedROIs(Class<T> roiClass, boolean sorted, boolean wantReadOnly)
    {
        final List<T> result = new ArrayList<T>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected() && roiClass.isInstance(roi))
                    result.add((T) roi);
        }

        // sort it if required
        if (sorted)
            Collections.sort(result, ROI.idComparator);

        return result;
    }

    /**
     * Returns all selected ROI of given class.
     * 
     * @param roiClass
     *        ROI class restriction
     * @param wantReadOnly
     *        also return ROI with read only state
     */
    public List<ROI> getSelectedROIs(Class<? extends ROI> roiClass, boolean wantReadOnly)
    {
        final List<ROI> result = new ArrayList<ROI>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected() && roiClass.isInstance(roi))
                    if (wantReadOnly || !roi.isReadOnly())
                        result.add(roi);
        }

        return result;
    }

    /**
     * Returns all selected ROI
     */
    public ArrayList<ROI> getSelectedROIs()
    {
        final ArrayList<ROI> result = new ArrayList<ROI>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isSelected())
                    result.add(roi);
        }

        return result;
    }

    /**
     * Returns all selected 2D ROI
     */
    public ArrayList<ROI2D> getSelectedROI2Ds()
    {
        final ArrayList<ROI2D> result = new ArrayList<ROI2D>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if ((roi instanceof ROI2D) && roi.isSelected())
                    result.add((ROI2D) roi);
        }

        return result;
    }

    /**
     * Returns all selected 3D ROI
     */
    public ArrayList<ROI3D> getSelectedROI3Ds()
    {
        final ArrayList<ROI3D> result = new ArrayList<ROI3D>(rois.size());

        synchronized (rois)
        {
            for (ROI roi : rois)
                if ((roi instanceof ROI3D) && roi.isSelected())
                    result.add((ROI3D) roi);
        }

        return result;
    }

    /**
     * Returns the current focused ROI (null if no ROI focused)
     */
    public ROI getFocusedROI()
    {
        synchronized (rois)
        {
            for (ROI roi : rois)
                if (roi.isFocused())
                    return roi;
        }

        return null;
    }

    /**
     * Set the selected ROI (exclusive selection).<br>
     * Specifying a <code>null</code> ROI here will actually clear all ROI selection.<br>
     * Note that you can use {@link #setSelectedROIs(List)} or {@link ROI#setSelected(boolean)} for
     * multiple ROI selection.
     * 
     * @param roi
     *        the ROI to select.
     * @return <code>false</code> is the specified ROI is not attached to the sequence.
     */
    public boolean setSelectedROI(ROI roi)
    {
        beginUpdate();
        try
        {
            synchronized (rois)
            {
                for (ROI currentRoi : rois)
                    if (currentRoi != roi)
                        currentRoi.setSelected(false);
            }

            if (contains(roi))
            {
                roi.setSelected(true);
                return true;
            }
        }
        finally
        {
            endUpdate();
        }

        return false;
    }

    /**
     * @deprecated Use {@link #setSelectedROI(ROI)} instead.
     */
    @Deprecated
    public boolean setSelectedROI(ROI roi, boolean exclusive)
    {
        if (exclusive)
            return setSelectedROI(roi);

        if (contains(roi))
        {
            roi.setSelected(true);
            return true;
        }

        return false;
    }

    /**
     * @deprecated Use {@link #setSelectedROIs(List)} instead.
     */
    @Deprecated
    public void setSelectedROIs(ArrayList<ROI> selected)
    {
        setSelectedROIs((List<ROI>) selected);
    }

    /**
     * Set selected ROI (unselected all others)
     */
    public void setSelectedROIs(List<? extends ROI> selected)
    {
        final List<ROI> oldSelected = getSelectedROIs();

        final int newSelectedSize = (selected == null) ? 0 : selected.size();
        final int oldSelectedSize = oldSelected.size();

        // easy optimization
        if ((newSelectedSize == 0) && (oldSelectedSize == 0))
            return;

        final HashSet<ROI> newSelected;

        // use HashSet for fast .contains() !
        if (selected != null)
            newSelected = new HashSet<ROI>(selected);
        else
            newSelected = new HashSet<ROI>();

        // selection changed ?
        if (!CollectionUtil.equals(oldSelected, newSelected))
        {
            beginUpdate();
            try
            {
                if (newSelectedSize > 0)
                {
                    for (ROI roi : getROIs())
                        roi.setSelected(newSelected.contains(roi));
                }
                else
                {
                    // unselected all ROIs
                    for (ROI roi : getROIs())
                        roi.setSelected(false);
                }
            }
            finally
            {
                endUpdate();
            }
        }
    }

    /**
     * Set the focused ROI
     */
    public boolean setFocusedROI(ROI roi)
    {
        // faster .contain()
        final Set<ROI> listRoi = getROISet();

        beginUpdate();
        try
        {
            for (ROI currentRoi : listRoi)
                if (currentRoi != roi)
                    currentRoi.internalUnfocus();

            if (listRoi.contains(roi))
            {
                roi.internalFocus();
                return true;
            }
        }
        finally
        {
            endUpdate();
        }

        return false;
    }

    /**
     * Add the specified collection of ROI to the sequence.
     * 
     * @param rois
     *        the collection of ROI to attach to the sequence
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     * @return <code>true</code> if the operation succeed or <code>false</code> if some ROIs could
     *         not be added (already present)
     */
    public boolean addROIs(Collection<? extends ROI> rois, boolean canUndo)
    {
        if (!rois.isEmpty())
        {
            final List<ROI> addedRois = new ArrayList<ROI>();

            for (ROI roi : rois)
            {
                if (addROI(roi, false))
                    addedRois.add(roi);
            }

            if (canUndo && !addedRois.isEmpty())
                addUndoableEdit(new ROIAddsSequenceEdit(this, addedRois));

            return addedRois.size() == rois.size();
        }

        return true;
    }

    /**
     * Add the specified ROI to the sequence.
     * 
     * @param roi
     *        ROI to attach to the sequence
     */
    public boolean addROI(ROI roi)
    {
        return addROI(roi, false);
    }

    /**
     * Add the specified ROI to the sequence.
     * 
     * @param roi
     *        ROI to attach to the sequence
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     * @return <code>true</code> if the operation succeed or <code>false</code> otherwise (already
     *         present)
     */
    public boolean addROI(ROI roi, boolean canUndo)
    {
        if ((roi == null) || contains(roi))
            return false;

        synchronized (rois)
        {
            rois.add(roi);
        }
        // add listener to ROI
        roi.addListener(this);
        // notify roi added
        roiChanged(roi, SequenceEventType.ADDED);
        // then add ROI overlay to sequence
        addOverlay(roi.getOverlay());

        if (canUndo)
            addUndoableEdit(new ROIAddSequenceEdit(this, roi));

        return true;

    }

    /**
     * Remove the specified ROI from the sequence.
     * 
     * @param roi
     *        ROI to detach from the sequence
     */
    public boolean removeROI(ROI roi)
    {
        return removeROI(roi, false);
    }

    /**
     * Remove the specified ROI from the sequence.
     * 
     * @param roi
     *        ROI to detach from the sequence
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     * @return <code>false</code> if the ROI was not found in the sequence.<br/>
     *         Returns <code>true</code> otherwise.
     */
    public boolean removeROI(ROI roi, boolean canUndo)
    {
        if (contains(roi))
        {
            // remove ROI overlay first
            removeOverlay(roi.getOverlay());

            // remove ROI
            synchronized (rois)
            {
                rois.remove(roi);
            }
            // remove listener
            roi.removeListener(this);
            // notify roi removed
            roiChanged(roi, SequenceEventType.REMOVED);

            if (canUndo)
                addUndoableEdit(new ROIRemoveSequenceEdit(this, roi));

            return true;
        }

        return false;
    }

    /**
     * Remove the specified collection of ROI from the sequence.
     * 
     * @param rois
     *        the collection of ROI to remove from the sequence
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     * @return <code>true</code> if all ROI from the collection has been correctly removed.
     */
    public boolean removeROIs(Collection<? extends ROI> rois, boolean canUndo)
    {
        if (!rois.isEmpty())
        {
            final List<ROI> removedRois = new ArrayList<ROI>();

            for (ROI roi : rois)
            {
                if (removeROI(roi, false))
                    removedRois.add(roi);
            }

            if (canUndo && !removedRois.isEmpty())
                addUndoableEdit(new ROIRemovesSequenceEdit(this, removedRois));

            return removedRois.size() == rois.size();
        }

        return true;
    }

    /**
     * Remove all selected ROI from the sequence.
     * 
     * @param removeReadOnly
     *        Specify if we should also remove <i>read only</i> ROI (see {@link ROI#isReadOnly()})
     * @return <code>true</code> if at least one ROI was removed.<br/>
     *         Returns <code>false</code> otherwise
     */
    public boolean removeSelectedROIs(boolean removeReadOnly)
    {
        return removeSelectedROIs(removeReadOnly, false);
    }

    /**
     * Remove all selected ROI from the sequence.
     * 
     * @param removeReadOnly
     *        Specify if we should also remove <i>read only</i> ROI (see {@link ROI#isReadOnly()})
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     * @return <code>true</code> if at least one ROI was removed.<br/>
     *         Returns <code>false</code> otherwise
     */
    public boolean removeSelectedROIs(boolean removeReadOnly, boolean canUndo)
    {
        final List<ROI> undoList = new ArrayList<ROI>();

        beginUpdate();
        try
        {
            synchronized (rois)
            {
                for (ROI roi : getROIs())
                {
                    if (roi.isSelected() && (removeReadOnly || !roi.isReadOnly()))
                    {
                        // remove ROI overlay first
                        removeOverlay(roi.getOverlay());

                        rois.remove(roi);
                        // remove listener
                        roi.removeListener(this);
                        // notify roi removed
                        roiChanged(roi, SequenceEventType.REMOVED);

                        // save deleted ROI
                        undoList.add(roi);
                    }
                }
            }

            if (canUndo)
                undoManager.addEdit(new ROIRemovesSequenceEdit(this, undoList));
        }
        finally
        {
            endUpdate();
        }

        return !undoList.isEmpty();
    }

    /**
     * Remove all ROI from the sequence.
     */
    public void removeAllROI()
    {
        removeAllROI(false);
    }

    /**
     * Remove all ROI from the sequence.
     * 
     * @param canUndo
     *        If true the action can be canceled by the undo manager.
     */
    public void removeAllROI(boolean canUndo)
    {
        if (!rois.isEmpty())
        {
            final List<ROI> allROIs = getROIs();

            // remove all ROI
            for (ROI roi : allROIs)
                removeROI(roi, false);

            if (canUndo)
                addUndoableEdit(new ROIRemovesSequenceEdit(this, allROIs));
        }
    }

    /**
     * Return the overlay associated to the specified painter.<br>
     * Used only for backward compatibility with {@link Painter} interface.
     */
    @SuppressWarnings("deprecation")
    protected Overlay getOverlay(Painter painter)
    {
        if (painter instanceof Overlay)
            return (Overlay) painter;

        synchronized (overlays)
        {
            for (Overlay overlay : overlays)
                if (overlay instanceof OverlayWrapper)
                    if (((OverlayWrapper) overlay).getPainter() == painter)
                        return overlay;
        }

        return null;
    }

    /**
     * @deprecated Use {@link #addOverlay(Overlay)} instead.
     */
    @Deprecated
    public boolean addPainter(Painter painter)
    {
        if (painter instanceof Overlay)
            return addOverlay((Overlay) painter);

        if ((painter == null) || contains(painter))
            return false;

        addOverlay(new OverlayWrapper(painter, "Overlay wrapper"));

        return true;
    }

    /**
     * @deprecated Use {@link #removeOverlay(Overlay)} instead.
     */
    @Deprecated
    public boolean removePainter(Painter painter)
    {
        if (painter instanceof Overlay)
            return removeOverlay((Overlay) painter);

        return removeOverlay(getOverlay(painter));
    }

    /**
     * Add an overlay to the sequence.
     */
    public boolean addOverlay(Overlay overlay)
    {
        if ((overlay == null) || contains(overlay))
            return false;

        synchronized (overlays)
        {
            overlays.add(overlay);
        }

        // add listener
        overlay.addOverlayListener(this);
        // notify overlay added
        overlayChanged(overlay, SequenceEventType.ADDED);

        return true;
    }

    /**
     * Remove an overlay from the sequence.
     */
    public boolean removeOverlay(Overlay overlay)
    {
        boolean result;

        synchronized (overlays)
        {
            result = overlays.remove(overlay);
        }

        if (result)
        {
            // remove listener
            overlay.removeOverlayListener(this);
            // notify overlay removed
            overlayChanged(overlay, SequenceEventType.REMOVED);
        }

        return result;
    }

    /**
     * Return <i>true</i> if image data at given position is loaded.
     */
    public boolean isDataLoaded(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z, false);

        return (img != null) && img.isDataInitialized();
    }

    /**
     * Returns the VolumetricImage at position t
     */
    public VolumetricImage getVolumetricImage(int t)
    {
        synchronized (volumetricImages)
        {
            return volumetricImages.get(Integer.valueOf(t));
        }
    }

    /**
     * Returns the first VolumetricImage
     */
    protected VolumetricImage getFirstVolumetricImage()
    {
        final Entry<Integer, VolumetricImage> entry;

        synchronized (volumetricImages)
        {
            entry = volumetricImages.firstEntry();
        }

        if (entry != null)
            return entry.getValue();

        return null;
    }

    /**
     * Returns the last VolumetricImage
     */
    protected VolumetricImage getLastVolumetricImage()
    {
        final Entry<Integer, VolumetricImage> entry;

        synchronized (volumetricImages)
        {
            entry = volumetricImages.lastEntry();
        }

        if (entry != null)
            return entry.getValue();

        return null;
    }

    /**
     * Add an empty volumetricImage at last index + 1
     */
    public VolumetricImage addVolumetricImage()
    {
        return setVolumetricImage(getSizeT());
    }

    /**
     * Add an empty volumetricImage at t position
     */
    protected VolumetricImage setVolumetricImage(int t)
    {
        // remove old volumetric image if any
        removeAllImages(t);

        final VolumetricImage volImg = new VolumetricImage(this);

        synchronized (volumetricImages)
        {
            volumetricImages.put(Integer.valueOf(t), volImg);
        }

        return volImg;
    }

    /**
     * Add a volumetricImage at t position<br>
     * It actually create a new volumetricImage and add it to the sequence<br>
     * The new created volumetricImage is returned
     */
    public VolumetricImage addVolumetricImage(int t, VolumetricImage volImg)
    {
        if (volImg != null)
        {
            final VolumetricImage result;

            beginUpdate();
            try
            {
                // get new volumetric image (remove old one if any)
                result = setVolumetricImage(t);

                for (Entry<Integer, IcyBufferedImage> entry : volImg.getImages().entrySet())
                    setImage(t, entry.getKey().intValue(), entry.getValue());
            }
            finally
            {
                endUpdate();
            }

            return result;
        }

        return null;
    }

    /**
     * @deprecated Use {@link #removeAllImages(int)} instead.
     */
    @Deprecated
    public boolean removeVolumetricImage(int t)
    {
        return removeAllImages(t);
    }

    /**
     * Returns the last image of VolumetricImage[t]
     */
    public IcyBufferedImage getLastImage(int t)
    {
        final VolumetricImage volImg = getVolumetricImage(t);

        if (volImg != null)
            return volImg.getLastImage();

        return null;
    }

    /**
     * Returns the first image of first VolumetricImage
     */
    public IcyBufferedImage getFirstImage()
    {
        final VolumetricImage volImg = getFirstVolumetricImage();

        if (volImg != null)
            return volImg.getFirstImage();

        return null;
    }

    /**
     * Returns the first non null image if exist
     */
    public IcyBufferedImage getFirstNonNullImage()
    {
        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
            {
                final IcyBufferedImage img = volImg.getFirstNonNullImage();
                if (img != null)
                    return img;
            }
        }

        return null;
    }

    /**
     * Returns the last image of last VolumetricImage
     */
    public IcyBufferedImage getLastImage()
    {
        final VolumetricImage volImg = getLastVolumetricImage();

        if (volImg != null)
            return volImg.getLastImage();

        return null;
    }

    /**
     * Returns a single component image corresponding to the component c of the image
     * at time t and depth z.<br>
     * This actually create a new image which share its data with internal image
     * so any modifications to one affect the other.<br>
     * if <code>(c == -1)</code> then this method is equivalent to {@link #getImage(int, int)}<br>
     * if <code>((c == 0) || (sizeC == 1))</code> then this method is equivalent to {@link #getImage(int, int)}<br>
     * if <code>((c < 0) || (c >= sizeC))</code> then it returns <code>null</code>
     * 
     * @see IcyBufferedImageUtil#extractChannel(IcyBufferedImage, int)
     * @since version 1.0.3.3b
     */
    @Override
    public IcyBufferedImage getImage(int t, int z, int c)
    {
        final IcyBufferedImage src = getImage(t, z);

        if ((src == null) || (c == -1))
            return src;

        return src.getImage(c);
    }

    /**
     * Returns image at time t and depth z.
     * 
     * @param loadData
     *        if <code>true</code> then we ensure that image data is loaded (in case of lazy loading) before returning the image
     */
    protected IcyBufferedImage getImage(int t, int z, boolean loadData)
    {
        final VolumetricImage volImg = getVolumetricImage(t);

        if (volImg != null)
        {
            final IcyBufferedImage result = volImg.getImage(z);

            if ((result != null) && loadData)
                result.loadData();

            return result;
        }

        return null;
    }

    /**
     * Returns image at time t and depth z
     */
    @Override
    public IcyBufferedImage getImage(int t, int z)
    {
        // get image (no data loading at this point)
        final IcyBufferedImage result = getImage(t, z, false);

        final int sizeZ = getSizeZ();
        final int sizeT = getSizeT();
        final int prefetchRange = 2;

        // dumb data prefetch around T
        for (int i = -prefetchRange; i <= prefetchRange; i++)
        {
            final int pt = t + i;

            if ((pt != t) && (pt >= 0) && (pt < sizeT))
                SequencePrefetcher.prefetch(this, pt, z);
        }
        // 3D stack ?
        if (z > 0)
        {
            // dumb data prefetch around current Z
            for (int i = -prefetchRange; i <= prefetchRange; i++)
            {
                final int pz = z + i;

                if ((pz != z) && (pz >= 0) && (pz < sizeZ))
                    SequencePrefetcher.prefetch(this, t, pz);
            }
        }

        return result;
    }

    /**
     * Returns all images at specified t position
     */
    public ArrayList<IcyBufferedImage> getImages(int t)
    {
        final VolumetricImage volImg = getVolumetricImage(t);

        if (volImg != null)
            return volImg.getAllImage();

        return new ArrayList<IcyBufferedImage>();
    }

    /**
     * Returns all images of sequence in [ZT] order:<br>
     * 
     * <pre>
     * T=0 Z=0
     * T=0 Z=1
     * T=0 Z=2
     * ...
     * T=1 Z=0
     * ...
     * </pre>
     */
    public ArrayList<IcyBufferedImage> getAllImage()
    {
        final ArrayList<IcyBufferedImage> result = new ArrayList<IcyBufferedImage>();

        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
                result.addAll(volImg.getAllImage());
        }

        return result;
    }

    /**
     * Put an image into the specified VolumetricImage at the given z location
     */
    protected void setImage(VolumetricImage volImg, int z, BufferedImage image) throws IllegalArgumentException
    {
        if (volImg != null)
        {
            // not the same image ?
            if (volImg.getImage(z) != image)
            {
                // this is different from removeImage as we don't remove empty VolumetricImage
                if (image == null)
                    volImg.removeImage(z);
                else
                {
                    IcyBufferedImage icyImg;

                    // convert to icyImage if needed
                    if (image instanceof IcyBufferedImage)
                        icyImg = (IcyBufferedImage) image;
                    else
                        icyImg = IcyBufferedImage.createFrom(image);

                    // possible type change ?
                    final boolean typeChange = (colorModel == null) || isEmpty()
                            || ((getNumImage() == 1) && (volImg.getImage(z) != null));

                    // not changing type and not compatible
                    if (!typeChange && !isCompatible(icyImg))
                        throw new IllegalArgumentException("Sequence.setImage: image is not compatible !");

                    // we want to share the same color space for all the sequence:
                    // colormap eats a lot of memory so it's better to keep one global and we never
                    // use colormap for single image anyway. But it's important to preserve the colormodel for each
                    // image though as it store the channel bounds informations.
                    if (colorModel != null)
                        icyImg.getIcyColorModel().setColorSpace(colorModel.getIcyColorSpace());

                    // set automatic channel update from sequence
                    icyImg.setAutoUpdateChannelBounds(getAutoUpdateChannelBounds());

                    // set image
                    volImg.setImage(z, icyImg);

                    // possible type change --> virtual state may have changed
                    if (typeChange)
                        metaChanged(ID_VIRTUAL);
                }
            }
        }
    }

    /**
     * Set an image at the specified position.<br/>
     * Note that the image will be transformed in IcyBufferedImage internally if needed
     * 
     * @param t
     *        T position
     * @param z
     *        Z position
     * @param image
     *        the image to set
     */
    public void setImage(int t, int z, BufferedImage image) throws IllegalArgumentException
    {
        final boolean volImgCreated;

        if (image == null)
            return;

        VolumetricImage volImg = getVolumetricImage(t);

        if (volImg == null)
        {
            volImg = setVolumetricImage(t);
            volImgCreated = true;
        }
        else
            volImgCreated = false;

        try
        {
            // set image
            setImage(volImg, z, image);
        }
        catch (IllegalArgumentException e)
        {
            // image set failed ? remove empty image list if needed
            if (volImgCreated)
                removeAllImages(t);
            // throw exception
            throw e;
        }
    }

    /**
     * Add an image (image is added in Z dimension).<br>
     * This method is equivalent to <code>setImage(max(getSizeT() - 1, 0), getSizeZ(t), image)</code>
     */
    public void addImage(BufferedImage image) throws IllegalArgumentException
    {
        final int t = Math.max(getSizeT() - 1, 0);

        setImage(t, getSizeZ(t), image);
    }

    /**
     * Add an image at specified T position.<br>
     * This method is equivalent to <code>setImage(t, getSizeZ(t), image)</code>
     */
    public void addImage(int t, BufferedImage image) throws IllegalArgumentException
    {
        setImage(t, getSizeZ(t), image);
    }

    /**
     * Remove the image at the specified position.
     */
    public boolean removeImage(int t, int z)
    {
        final VolumetricImage volImg = getVolumetricImage(t);

        if (volImg != null)
        {
            final boolean result;

            beginUpdate();
            try
            {
                result = volImg.removeImage(z);

                // empty ?
                if (volImg.isEmpty())
                    // remove it
                    removeAllImages(t);
            }
            finally
            {
                endUpdate();
            }

            return result;
        }

        return false;
    }

    /**
     * Remove all images at position <code>t</code>
     */
    public boolean removeAllImages(int t)
    {
        final VolumetricImage volImg;

        synchronized (volumetricImages)
        {
            volImg = volumetricImages.remove(Integer.valueOf(t));
        }

        // we do manual clear to dispatch events correctly
        if (volImg != null)
            volImg.clear();

        return volImg != null;
    }

    /**
     * Remove all images
     */
    public void removeAllImages()
    {
        beginUpdate();
        try
        {
            synchronized (volumetricImages)
            {
                while (!volumetricImages.isEmpty())
                {
                    final VolumetricImage volImg = volumetricImages.pollFirstEntry().getValue();
                    // we do manual clear to dispatch events correctly
                    if (volImg != null)
                        volImg.clear();
                }
            }
        }
        finally
        {
            endUpdate();
        }
    }

    /**
     * @deprecated Use {@link #removeAllImages(int)} instead.
     */
    @Deprecated
    public boolean removeAllImage(int t)
    {
        return removeAllImages(t);
    }

    /**
     * @deprecated Use {@link #removeAllImages()} instead.
     */
    @Deprecated
    public void removeAllImage()
    {
        removeAllImages();
    }

    /**
     * Remove empty element of image list
     */
    public void packImageList()
    {
        beginUpdate();
        try
        {
            synchronized (volumetricImages)
            {
                for (Entry<Integer, VolumetricImage> entry : volumetricImages.entrySet())
                {
                    final VolumetricImage volImg = entry.getValue();
                    final int t = entry.getKey().intValue();

                    if (volImg == null)
                    {
                        removeAllImages(t);
                    }
                    else
                    {
                        // pack the list
                        volImg.pack();
                        // empty ? --> remove it
                        if (volImg.isEmpty())
                            removeAllImages(t);
                    }
                }
            }
        }
        finally
        {
            endUpdate();
        }
    }

    /**
     * return the number of loaded image
     */
    public int getNumImage()
    {
        int result = 0;

        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
                if (volImg != null)
                    result += volImg.getNumImage();
        }

        return result;
    }

    /**
     * return true if no image in sequence
     */
    public boolean isEmpty()
    {
        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
                if ((volImg != null) && (!volImg.isEmpty()))
                    return false;
        }

        return true;
    }

    /**
     * Returns true if the sequence uses default attributed name
     */
    public boolean isDefaultName()
    {
        return getName().startsWith(DEFAULT_NAME);
    }

    /**
     * Returns true is the specified channel uses default attributed name
     */
    public boolean isDefaultChannelName(int index)
    {
        return StringUtil.equals(getChannelName(index), getDefaultChannelName(index));
    }

    /**
     * Returns the number of volumetricImage in the sequence<br>
     * Use getSizeT instead
     * 
     * @see #getSizeT
     * @deprecated
     */
    @Deprecated
    public int getLength()
    {
        return getSizeT();
    }

    /**
     * return the number of volumetricImage in the sequence
     */
    @Override
    public int getSizeT()
    {
        synchronized (volumetricImages)
        {
            if (volumetricImages.isEmpty())
                return 0;

            return volumetricImages.lastKey().intValue() + 1;
        }
    }

    /**
     * Returns the global number of z stack in the sequence.
     * Use getSizeZ instead
     * 
     * @see #getSizeZ
     * @deprecated
     */
    @Deprecated
    public int getDepth()
    {
        return getSizeZ();
    }

    /**
     * Returns the global number of z stack in the sequence.
     */
    @Override
    public int getSizeZ()
    {
        final int sizeT = getSizeT();

        int result = 0;
        for (int i = 0; i < sizeT; i++)
            result = Math.max(result, getSizeZ(i));

        return result;
    }

    /**
     * Returns the number of z stack for the volumetricImage[t].
     */
    public int getSizeZ(int t)
    {
        // t = -1 means global Z size
        if (t == -1)
            return getSizeZ();

        final VolumetricImage volImg = getVolumetricImage(t);

        if (volImg != null)
            return volImg.getSize();

        return 0;
    }

    /**
     * Returns the number of component/channel/band per image.<br>
     * Use getSizeC instead
     * 
     * @see #getSizeC
     * @deprecated
     */
    @Deprecated
    public int getNumComponents()
    {
        return getSizeC();
    }

    /**
     * Returns the number of component/channel/band per image
     */
    @Override
    public int getSizeC()
    {
        // color model defined ? --> get it from color model
        if (colorModel != null)
            return colorModel.getNumComponents();

        // else try to get it from metadata
        return MetaDataUtil.getSizeC(metaData, 0);
    }

    /**
     * Same as {@link #getSizeY()}
     */
    public int getHeight()
    {
        return getSizeY();
    }

    /**
     * Returns the height of the sequence (0 if the sequence contains no image).
     */
    @Override
    public int getSizeY()
    {
        // try to get from image first
        final IcyBufferedImage img = getFirstNonNullImage();

        if (img != null)
            return img.getHeight();

        // else try to get from metadata
        return MetaDataUtil.getSizeY(metaData, 0);
    }

    /**
     * Same as {@link #getSizeX()}
     */
    public int getWidth()
    {
        return getSizeX();
    }

    /**
     * Returns the width of the sequence (0 if the sequence contains no image).
     */
    @Override
    public int getSizeX()
    {
        final IcyBufferedImage img = getFirstNonNullImage();

        // try to get it from image first
        if (img != null)
            return img.getWidth();

        // else try to get from metadata
        return MetaDataUtil.getSizeX(metaData, 0);
    }

    /**
     * Returns the size of the specified dimension
     */
    public int getSize(DimensionId dim)
    {
        switch (dim)
        {
            case X:
                return getSizeX();
            case Y:
                return getSizeY();
            case C:
                return getSizeC();
            case Z:
                return getSizeZ();
            case T:
                return getSizeT();
            default:
            case NULL:
                return 0;
        }
    }

    /**
     * Returns 2D dimension of sequence {sizeX, sizeY}
     */
    public Dimension getDimension2D()
    {
        return new Dimension(getSizeX(), getSizeY());
    }

    /**
     * Returns 5D dimension of sequence {sizeX, sizeY, sizeZ, sizeT, sizeC}
     */
    public Dimension5D.Integer getDimension5D()
    {
        return new Dimension5D.Integer(getSizeX(), getSizeY(), getSizeZ(), getSizeT(), getSizeC());
    }

    /**
     * @deprecated Use {@link #getDimension2D()} instead.
     */
    @Deprecated
    public Dimension getDimension()
    {
        return getDimension2D();
    }

    /**
     * Returns 2D bounds of sequence {0, 0, sizeX, sizeY}
     * 
     * @see #getDimension2D()
     */
    public Rectangle getBounds2D()
    {
        return new Rectangle(getSizeX(), getSizeY());
    }

    /**
     * Returns 5D bounds of sequence {0, 0, 0, 0, 0, sizeX, sizeY, sizeZ, sizeT, sizeC}
     * 
     * @see #getDimension5D()
     */
    public Rectangle5D.Integer getBounds5D()
    {
        return new Rectangle5D.Integer(0, 0, 0, 0, 0, getSizeX(), getSizeY(), getSizeZ(), getSizeT(), getSizeC());
    }

    /**
     * @deprecated Use {@link #getBounds2D()} instead
     */
    @Deprecated
    public Rectangle getBounds()
    {
        return getBounds2D();
    }

    /**
     * Returns the number of sample.<br>
     * This is equivalent to<br>
     * <code>getSizeX() * getSizeY() * getSizeC() * getSizeZ() * getSizeT()</code>
     */
    public int getNumSample()
    {
        return getSizeX() * getSizeY() * getSizeC() * getSizeZ() * getSizeT();
    }

    /**
     * Test if the specified image is compatible with current loaded images in sequence
     */
    public boolean isCompatible(IcyBufferedImage image)
    {
        if ((colorModel == null) || isEmpty())
            return true;

        return (image.getWidth() == getWidth()) && (image.getHeight() == getHeight())
                && isCompatible(image.getIcyColorModel());
    }

    /**
     * Test if the specified colorModel is compatible with sequence colorModel
     */
    public boolean isCompatible(IcyColorModel cm)
    {
        // test that colorModel are compatible
        if (colorModel == null)
            return true;

        return colorModel.isCompatible(cm);
    }

    /**
     * Returns true if specified LUT is compatible with sequence LUT
     */
    public boolean isLutCompatible(LUT lut)
    {
        IcyColorModel cm = colorModel;
        // not yet defined ? use default one
        if (cm == null)
            cm = IcyColorModel.createInstance();

        return lut.isCompatible(cm);
    }

    /**
     * Returns the colorModel
     */
    public IcyColorModel getColorModel()
    {
        return colorModel;
    }

    /**
     * Same as {@link #createCompatibleLUT()}
     */
    public LUT getDefaultLUT()
    {
        // color model not anymore compatible with user LUT --> reset it
        if ((defaultLut == null) || ((colorModel != null) && !defaultLut.isCompatible(colorModel)))
            defaultLut = createCompatibleLUT();

        return defaultLut;
    }

    /**
     * Returns <code>true</code> if a user LUT has be defined for this sequence.
     */
    public boolean hasUserLUT()
    {
        return (userLut != null);
    }

    /**
     * Returns the users LUT.<br>
     * If user LUT is not defined then a new default LUT is returned.
     * 
     * @see #getDefaultLUT()
     */
    public LUT getUserLUT()
    {
        // color model not anymore compatible with user LUT --> reset it
        if ((userLut == null) || ((colorModel != null) && !userLut.isCompatible(colorModel)))
            userLut = getDefaultLUT();

        return userLut;
    }

    /**
     * Sets the user LUT (saved in XML persistent metadata).
     */
    public void setUserLUT(LUT lut)
    {
        if ((colorModel == null) || lut.isCompatible(colorModel))
            userLut = lut;
    }

    /**
     * Creates and returns the default LUT for this sequence.<br>
     * If the sequence is empty it returns a default ARGB LUT.
     */
    public LUT createCompatibleLUT()
    {
        final IcyColorModel result;

        // not yet defined ? use default one
        if (colorModel == null)
            result = IcyColorModel.createInstance();
        else
            result = IcyColorModel.createInstance(colorModel, true, true);

        return new LUT(result);
    }

    /**
     * Get the default colormap for the specified channel
     * 
     * @param channel
     *        channel we want to set the colormap
     * @see #getColorMap(int)
     */
    public IcyColorMap getDefaultColorMap(int channel)
    {
        if (colorModel != null)
            return colorModel.getColorMap(channel);

        return getDefaultLUT().getLutChannel(channel).getColorMap();
    }

    /**
     * Set the default colormap for the specified channel
     * 
     * @param channel
     *        channel we want to set the colormap
     * @param map
     *        source colormap to copy
     * @param setAlpha
     *        also copy the alpha information
     * @see #getDefaultColorMap(int)
     */
    public void setDefaultColormap(int channel, IcyColorMap map, boolean setAlpha)
    {
        if (colorModel != null)
            colorModel.setColorMap(channel, map, setAlpha);
    }

    /**
     * Set the default colormap for the specified channel
     * 
     * @param channel
     *        channel we want to set the colormap
     * @param map
     *        source colormap to copy
     * @see #getDefaultColorMap(int)
     */
    public void setDefaultColormap(int channel, IcyColorMap map)
    {
        setDefaultColormap(channel, map, map.isAlpha());
    }

    /**
     * Get the user colormap for the specified channel.<br>
     * User colormap is saved in the XML persistent data and reloaded when opening the Sequence.
     * 
     * @param channel
     *        channel we want to set the colormap
     * @see #getDefaultColorMap(int)
     */
    public IcyColorMap getColorMap(int channel)
    {
        final LUT lut = getUserLUT();

        if (channel < lut.getNumChannel())
            return lut.getLutChannel(channel).getColorMap();

        return null;
    }

    /**
     * Set the user colormap for the specified channel.<br>
     * User colormap is saved in the XML persistent data and reloaded when opening the Sequence.
     * 
     * @param channel
     *        channel we want to set the colormap
     * @param map
     *        source colormap to copy
     * @param setAlpha
     *        also copy the alpha information
     * @see #getColorMap(int)
     */
    public void setColormap(int channel, IcyColorMap map, boolean setAlpha)
    {
        final LUT lut = getUserLUT();

        if (channel < lut.getNumChannel())
            lut.getLutChannel(channel).setColorMap(map, setAlpha);
    }

    /**
     * Set the user colormap for the specified channel.<br>
     * User colormap is saved in the XML persistent data and reloaded when opening the Sequence.
     * 
     * @param channel
     *        channel we want to set the colormap
     * @param map
     *        source colormap to copy
     * @see #getColorMap(int)
     */
    public void setColormap(int channel, IcyColorMap map)
    {
        setColormap(channel, map, map.isAlpha());
    }

    /**
     * Returns the data type of sequence
     */
    public DataType getDataType_()
    {
        // assume unsigned byte by default
        if (colorModel == null)
            // preserve UNDEFINED here for backward compatibility (Math Operation for instance)
            return DataType.UNDEFINED;

        return colorModel.getDataType_();
    }

    /**
     * Returns the data type of sequence
     * 
     * @deprecated use {@link #getDataType_()} instead
     */
    @Deprecated
    public int getDataType()
    {
        if (colorModel == null)
            return TypeUtil.TYPE_UNDEFINED;

        return colorModel.getDataType();
    }

    /**
     * Returns true if this is a float data type sequence
     */
    public boolean isFloatDataType()
    {
        return getDataType_().isFloat();
    }

    /**
     * Returns true if this is a signed data type sequence
     */
    public boolean isSignedDataType()
    {
        return getDataType_().isSigned();
    }

    /**
     * Internal use only.
     */
    private static double[][] adjustBounds(double[][] curBounds, double[][] bounds)
    {
        if (bounds == null)
            return curBounds;

        for (int comp = 0; comp < bounds.length; comp++)
        {
            final double[] compBounds = bounds[comp];
            final double[] curCompBounds = curBounds[comp];

            if (curCompBounds[0] < compBounds[0])
                compBounds[0] = curCompBounds[0];
            if (curCompBounds[1] > compBounds[1])
                compBounds[1] = curCompBounds[1];
        }

        return bounds;
    }

    /**
     * Recalculate all image channels bounds (min and max values).<br>
     * Internal use only.
     */
    protected void recalculateAllImageChannelsBounds()
    {
        // nothing to do...
        if ((colorModel == null) || isEmpty())
            return;

        final List<VolumetricImage> volumes = getAllVolumetricImage();

        beginUpdate();
        try
        {
            // recalculate images bounds (automatically update sequence bounds with event)
            for (VolumetricImage volImg : volumes)
                for (IcyBufferedImage img : volImg.getAllImage())
                    img.updateChannelsBounds();
        }
        finally
        {
            endUpdate();
        }
    }

    /**
     * Update channels bounds (min and max values)<br>
     * At this point we assume images has correct channels bounds information.<br>
     * Internal use only.
     */
    protected void internalUpdateChannelsBounds()
    {
        // nothing to do...
        if ((colorModel == null) || isEmpty())
            return;

        double[][] bounds;

        bounds = null;
        // recalculate bounds from all images
        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
            {
                for (IcyBufferedImage img : volImg.getAllImage())
                {
                    if (img != null)
                        bounds = adjustBounds(img.getChannelsTypeBounds(), bounds);
                }
            }
        }

        // set new computed bounds
        colorModel.setComponentsAbsBounds(bounds);

        bounds = null;
        // recalculate user bounds from all images
        synchronized (volumetricImages)
        {
            for (VolumetricImage volImg : volumetricImages.values())
            {
                for (IcyBufferedImage img : volImg.getAllImage())
                {
                    if (img != null)
                        bounds = adjustBounds(img.getChannelsBounds(), bounds);
                }
            }
        }

        // set new computed bounds
        colorModel.setComponentsUserBounds(bounds);
    }

    /**
     * Update channels bounds (min and max values).<br>
     * 
     * @param forceRecalculation
     *        If true we force all images channels bounds recalculation (this can take sometime). <br>
     *        You can left this flag to false if sequence images have their bounds updated (which
     *        should be the case by default).
     */
    public void updateChannelsBounds(boolean forceRecalculation)
    {
        // force calculation of all images bounds
        if (forceRecalculation)
            recalculateAllImageChannelsBounds();
        // then update sequence bounds
        internalUpdateChannelsBounds();
    }

    /**
     * Update channels bounds (min and max values).<br>
     * All images channels bounds are recalculated (this can take sometime).
     */
    public void updateChannelsBounds()
    {
        // force recalculation
        updateChannelsBounds(true);
    }

    /**
     * @deprecated Use {@link #updateChannelsBounds(boolean)} instead.
     */
    @Deprecated
    public void updateComponentsBounds(boolean forceRecalculation, boolean adjustByteToo)
    {
        updateChannelsBounds(forceRecalculation);
    }

    /**
     * @deprecated Use {@link #updateChannelsBounds(boolean)} instead.
     */
    @Deprecated
    public void updateComponentsBounds(boolean forceRecalculation)
    {
        updateChannelsBounds(forceRecalculation);
    }

    /**
     * @deprecated Use {@link #updateChannelsBounds(boolean)} instead.
     */
    @Deprecated
    public void updateComponentsBounds()
    {
        // force recalculation
        updateChannelsBounds(true);
    }

    /**
     * Get the data type minimum value.
     */
    public double getDataTypeMin()
    {
        return getDataType_().getMinValue();
    }

    /**
     * Get the data type maximum value.
     */
    public double getDataTypeMax()
    {
        return getDataType_().getMaxValue();
    }

    /**
     * Get data type bounds (min and max values).
     */
    public double[] getDataTypeBounds()
    {
        return new double[] {getDataTypeMin(), getDataTypeMax()};
    }

    /**
     * Get the preferred data type minimum value in the whole sequence for the specified channel.
     */
    public double getChannelTypeMin(int channel)
    {
        if (colorModel == null)
            return 0d;

        return colorModel.getComponentAbsMinValue(channel);
    }

    /**
     * Get the preferred data type maximum value in the whole sequence for the specified channel.
     */
    public double getChannelTypeMax(int channel)
    {
        if (colorModel == null)
            return 0d;

        return colorModel.getComponentAbsMaxValue(channel);
    }

    /**
     * Get the preferred data type bounds (min and max values) in the whole sequence for the
     * specified channel.
     */
    public double[] getChannelTypeBounds(int channel)
    {
        if (colorModel == null)
            return new double[] {0d, 0d};

        return colorModel.getComponentAbsBounds(channel);
    }

    /**
     * Get the preferred data type bounds (min and max values) in the whole sequence for all
     * channels.
     */
    public double[][] getChannelsTypeBounds()
    {
        final int sizeC = getSizeC();
        final double[][] result = new double[sizeC][];

        for (int c = 0; c < sizeC; c++)
            result[c] = getChannelTypeBounds(c);

        return result;
    }

    /**
     * Get the global preferred data type bounds (min and max values) for all channels.
     */
    public double[] getChannelsGlobalTypeBounds()
    {
        final int sizeC = getSizeC();
        final double[] result = getChannelTypeBounds(0);

        for (int c = 1; c < sizeC; c++)
        {
            final double[] bounds = getChannelTypeBounds(c);
            result[0] = Math.min(bounds[0], result[0]);
            result[1] = Math.max(bounds[1], result[1]);
        }

        return result;
    }

    /**
     * @deprecated Use {@link #getChannelsGlobalTypeBounds()} instead
     */
    @Deprecated
    public double[] getChannelTypeGlobalBounds()
    {
        return getChannelsGlobalTypeBounds();
    }

    /**
     * @deprecated Use {@link #getChannelTypeGlobalBounds()} instead.
     */
    @Deprecated
    public double[] getGlobalChannelTypeBounds()
    {
        return getChannelTypeGlobalBounds();
    }

    /**
     * @deprecated Use {@link #getChannelTypeMin(int)} instead.
     */
    @Deprecated
    public double getComponentAbsMinValue(int component)
    {
        return getChannelTypeMin(component);
    }

    /**
     * @deprecated Use {@link #getChannelTypeMax(int)} instead.
     */
    @Deprecated
    public double getComponentAbsMaxValue(int component)
    {
        return getChannelTypeMax(component);
    }

    /**
     * @deprecated Use {@link #getChannelTypeBounds(int)} instead.
     */
    @Deprecated
    public double[] getComponentAbsBounds(int component)
    {
        return getChannelTypeBounds(component);
    }

    /**
     * @deprecated Use {@link #getChannelsTypeBounds()} instead.
     */
    @Deprecated
    public double[][] getComponentsAbsBounds()
    {
        return getChannelsTypeBounds();
    }

    /**
     * @deprecated Use {@link #getChannelsGlobalTypeBounds()} instead.
     */
    @Deprecated
    public double[] getGlobalComponentAbsBounds()
    {
        return getChannelsGlobalTypeBounds();
    }

    /**
     * Get the minimum value in the whole sequence for the specified channel.
     */
    public double getChannelMin(int channel)
    {
        if (colorModel == null)
            return 0d;

        return colorModel.getComponentUserMinValue(channel);
    }

    /**
     * Get maximum value in the whole sequence for the specified channel.
     */
    public double getChannelMax(int channel)
    {
        if (colorModel == null)
            return 0d;

        return colorModel.getComponentUserMaxValue(channel);
    }

    /**
     * Get bounds (min and max values) in the whole sequence for the specified channel.
     */
    public double[] getChannelBounds(int channel)
    {
        if (colorModel == null)
            return new double[] {0d, 0d};

        // lazy channel bounds update
        if (channelBoundsInvalid)
        {
            channelBoundsInvalid = false;
            // images channels bounds are valid at this point
            internalUpdateChannelsBounds();
        }

        return colorModel.getComponentUserBounds(channel);
    }

    /**
     * Get bounds (min and max values) in the whole sequence for all channels.
     */
    public double[][] getChannelsBounds()
    {
        final int sizeC = getSizeC();
        final double[][] result = new double[sizeC][];

        for (int c = 0; c < sizeC; c++)
            result[c] = getChannelBounds(c);

        return result;
    }

    /**
     * Get global bounds (min and max values) in the whole sequence for all channels.
     */
    public double[] getChannelsGlobalBounds()
    {
        final int sizeC = getSizeC();
        final double[] result = new double[2];

        result[0] = Double.MAX_VALUE;
        result[1] = -Double.MAX_VALUE;

        for (int c = 0; c < sizeC; c++)
        {
            final double[] bounds = getChannelBounds(c);

            if (bounds[0] < result[0])
                result[0] = bounds[0];
            if (bounds[1] > result[1])
                result[1] = bounds[1];
        }

        return result;
    }

    /**
     * @deprecated Use {@link #getChannelMin(int)} instead.
     */
    @Deprecated
    public double getComponentUserMinValue(int component)
    {
        return getChannelMin(component);
    }

    /**
     * @deprecated Use {@link #getChannelMax(int)} instead.
     */
    @Deprecated
    public double getComponentUserMaxValue(int component)
    {
        return getChannelMax(component);
    }

    /**
     * @deprecated Use {@link #getChannelBounds(int)} instead.
     */
    @Deprecated
    public double[] getComponentUserBounds(int component)
    {
        return getChannelBounds(component);
    }

    /**
     * @deprecated Use {@link #getChannelsBounds()} instead.
     */
    @Deprecated
    public double[][] getComponentsUserBounds()
    {
        return getChannelsBounds();
    }

    /**
     * Force all image data to be loaded (so channels bounds can be correctly computed).<br>
     * Be careful, this function can take sometime.
     */
    public void loadAllData()
    {
        for (IcyBufferedImage image : getAllImage())
            if (image != null)
                image.loadData();
    }

    /**
     * Returns the data value located at position (t, z, c, y, x) as double.<br>
     * It returns 0d if value is not found.
     */
    public double getData(int t, int z, int c, int y, int x)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getData(x, y, c);

        return 0d;
    }

    /**
     * Returns the data value located at position (t, z, c, y, x) as double.<br>
     * The value is interpolated depending the current double (x,y,z) coordinates.<br>
     * It returns 0d if value is out of range.
     */
    public double getDataInterpolated(int t, double z, int c, double y, double x)
    {
        final int zi = (int) z;
        final double ratioNextZ = z - (double) zi;

        double result = 0d;
        IcyBufferedImage img;

        img = getImage(t, zi);
        if (img != null)
        {
            final double ratioCurZ = 1d - ratioNextZ;
            if (ratioCurZ > 0d)
                result += img.getDataInterpolated(x, y, c) * ratioCurZ;
        }
        img = getImage(t, zi + 1);
        if (img != null)
        {
            if (ratioNextZ > 0d)
                result += img.getDataInterpolated(x, y, c) * ratioNextZ;
        }

        return result;
    }

    /**
     * Returns a direct reference to 4D array data [T][Z][C][XY]
     */
    public Object getDataXYCZT()
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataXYCZTAsByte();
            case SHORT:
                return getDataXYCZTAsShort();
            case INT:
                return getDataXYCZTAsInt();
            case FLOAT:
                return getDataXYCZTAsFloat();
            case DOUBLE:
                return getDataXYCZTAsDouble();
            default:
                return null;
        }
    }

    /**
     * Returns a direct reference to 3D array data [Z][C][XY] for specified t
     */
    public Object getDataXYCZ(int t)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataXYCZAsByte(t);
            case SHORT:
                return getDataXYCZAsShort(t);
            case INT:
                return getDataXYCZAsInt(t);
            case FLOAT:
                return getDataXYCZAsFloat(t);
            case DOUBLE:
                return getDataXYCZAsDouble(t);
            default:
                return null;
        }
    }

    /**
     * Returns a direct reference to 2D array data [C][XY] for specified t, z
     */
    public Object getDataXYC(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYC();

        return null;
    }

    /**
     * Returns a direct reference to 1D array data [XY] for specified t, z, c
     */
    public Object getDataXY(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXY(c);

        return null;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public Object getDataXYZT(int c)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataXYZTAsByte(c);
            case SHORT:
                return getDataXYZTAsShort(c);
            case INT:
                return getDataXYZTAsInt(c);
            case FLOAT:
                return getDataXYZTAsFloat(c);
            case DOUBLE:
                return getDataXYZTAsDouble(c);
            default:
                return null;
        }
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public Object getDataXYZ(int t, int c)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataXYZAsByte(t, c);
            case SHORT:
                return getDataXYZAsShort(t, c);
            case INT:
                return getDataXYZAsInt(t, c);
            case FLOAT:
                return getDataXYZAsFloat(t, c);
            case DOUBLE:
                return getDataXYZAsDouble(t, c);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public Object getDataCopyXYCZT()
    {
        return getDataCopyXYCZT(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXYCZT(Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyXYCZTAsByte((byte[]) out, off);
            case SHORT:
                return getDataCopyXYCZTAsShort((short[]) out, off);
            case INT:
                return getDataCopyXYCZTAsInt((int[]) out, off);
            case FLOAT:
                return getDataCopyXYCZTAsFloat((float[]) out, off);
            case DOUBLE:
                return getDataCopyXYCZTAsDouble((double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public Object getDataCopyXYCZ(int t)
    {
        return getDataCopyXYCZ(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXYCZ(int t, Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyXYCZAsByte(t, (byte[]) out, off);
            case SHORT:
                return getDataCopyXYCZAsShort(t, (short[]) out, off);
            case INT:
                return getDataCopyXYCZAsInt(t, (int[]) out, off);
            case FLOAT:
                return getDataCopyXYCZAsFloat(t, (float[]) out, off);
            case DOUBLE:
                return getDataCopyXYCZAsDouble(t, (double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public Object getDataCopyXYC(int t, int z)
    {
        return getDataCopyXYC(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXYC(int t, int z, Object out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYC(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public Object getDataCopyXY(int t, int z, int c)
    {
        return getDataCopyXY(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXY(int t, int z, int c, Object out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXY(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public Object getDataCopyCXYZT()
    {
        return getDataCopyCXYZT(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyCXYZT(Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyCXYZTAsByte((byte[]) out, off);
            case SHORT:
                return getDataCopyCXYZTAsShort((short[]) out, off);
            case INT:
                return getDataCopyCXYZTAsInt((int[]) out, off);
            case FLOAT:
                return getDataCopyCXYZTAsFloat((float[]) out, off);
            case DOUBLE:
                return getDataCopyCXYZTAsDouble((double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public Object getDataCopyCXYZ(int t)
    {
        return getDataCopyCXYZ(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyCXYZ(int t, Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyCXYZAsByte(t, (byte[]) out, off);
            case SHORT:
                return getDataCopyCXYZAsShort(t, (short[]) out, off);
            case INT:
                return getDataCopyCXYZAsInt(t, (int[]) out, off);
            case FLOAT:
                return getDataCopyCXYZAsFloat(t, (float[]) out, off);
            case DOUBLE:
                return getDataCopyCXYZAsDouble(t, (double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public Object getDataCopyCXY(int t, int z)
    {
        return getDataCopyCXY(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyCXY(int t, int z, Object out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXY(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public Object getDataCopyC(int t, int z, int x, int y)
    {
        return getDataCopyC(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyC(int t, int z, int x, int y, Object out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyC(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public Object getDataCopyXYZT(int c)
    {
        return getDataCopyXYZT(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXYZT(int c, Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyXYZTAsByte(c, (byte[]) out, off);
            case SHORT:
                return getDataCopyXYZTAsShort(c, (short[]) out, off);
            case INT:
                return getDataCopyXYZTAsInt(c, (int[]) out, off);
            case FLOAT:
                return getDataCopyXYZTAsFloat(c, (float[]) out, off);
            case DOUBLE:
                return getDataCopyXYZTAsDouble(c, (double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public Object getDataCopyXYZ(int t, int c)
    {
        return getDataCopyXYZ(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public Object getDataCopyXYZ(int t, int c, Object out, int off)
    {
        switch (getDataType_().getJavaType())
        {
            case BYTE:
                return getDataCopyXYZAsByte(t, c, (byte[]) out, off);
            case SHORT:
                return getDataCopyXYZAsShort(t, c, (short[]) out, off);
            case INT:
                return getDataCopyXYZAsInt(t, c, (int[]) out, off);
            case FLOAT:
                return getDataCopyXYZAsFloat(t, c, (float[]) out, off);
            case DOUBLE:
                return getDataCopyXYZAsDouble(t, c, (double[]) out, off);
            default:
                return null;
        }
    }

    /**
     * Returns a direct reference to 4D byte array data [T][Z][C][XY]
     */
    public byte[][][][] getDataXYCZTAsByte()
    {
        final int sizeT = getSizeT();
        final byte[][][][] result = new byte[sizeT][][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYCZAsByte(t);

        return result;

    }

    /**
     * Returns a direct reference to 4D byte array data [T][Z][C][XY]
     */
    public short[][][][] getDataXYCZTAsShort()
    {
        final int sizeT = getSizeT();
        final short[][][][] result = new short[sizeT][][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYCZAsShort(t);

        return result;
    }

    /**
     * Returns a direct reference to 4D byte array data [T][Z][C][XY]
     */
    public int[][][][] getDataXYCZTAsInt()
    {
        final int sizeT = getSizeT();
        final int[][][][] result = new int[sizeT][][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYCZAsInt(t);

        return result;
    }

    /**
     * Returns a direct reference to 4D byte array data [T][Z][C][XY]
     */
    public float[][][][] getDataXYCZTAsFloat()
    {
        final int sizeT = getSizeT();
        final float[][][][] result = new float[sizeT][][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYCZAsFloat(t);

        return result;
    }

    /**
     * Returns a direct reference to 4D byte array data [T][Z][C][XY]
     */
    public double[][][][] getDataXYCZTAsDouble()
    {
        final int sizeT = getSizeT();
        final double[][][][] result = new double[sizeT][][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYCZAsDouble(t);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [Z][C][XY] for specified t
     */
    public byte[][][] getDataXYCZAsByte(int t)
    {
        final int sizeZ = getSizeZ(t);
        final byte[][][] result = new byte[sizeZ][][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYCAsByte(t, z);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [Z][C][XY] for specified t
     */
    public short[][][] getDataXYCZAsShort(int t)
    {
        final int sizeZ = getSizeZ(t);
        final short[][][] result = new short[sizeZ][][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYCAsShort(t, z);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [Z][C][XY] for specified t
     */
    public int[][][] getDataXYCZAsInt(int t)
    {
        final int sizeZ = getSizeZ(t);
        final int[][][] result = new int[sizeZ][][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYCAsInt(t, z);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [Z][C][XY] for specified t
     */
    public float[][][] getDataXYCZAsFloat(int t)
    {
        final int sizeZ = getSizeZ(t);
        final float[][][] result = new float[sizeZ][][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYCAsFloat(t, z);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [Z][C][XY] for specified t
     */
    public double[][][] getDataXYCZAsDouble(int t)
    {
        final int sizeZ = getSizeZ(t);
        final double[][][] result = new double[sizeZ][][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYCAsDouble(t, z);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [C][XY] for specified t, z
     */
    public byte[][] getDataXYCAsByte(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYCAsByte();

        return null;
    }

    /**
     * Returns a direct reference to 2D byte array data [C][XY] for specified t, z
     */
    public short[][] getDataXYCAsShort(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYCAsShort();

        return null;
    }

    /**
     * Returns a direct reference to 2D byte array data [C][XY] for specified t, z
     */
    public int[][] getDataXYCAsInt(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYCAsInt();

        return null;
    }

    /**
     * Returns a direct reference to 2D byte array data [C][XY] for specified t, z
     */
    public float[][] getDataXYCAsFloat(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYCAsFloat();

        return null;
    }

    /**
     * Returns a direct reference to 2D byte array data [C][XY] for specified t, z
     */
    public double[][] getDataXYCAsDouble(int t, int z)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYCAsDouble();

        return null;
    }

    /**
     * Returns a direct reference to 1D byte array data [XY] for specified t, z, c
     */
    public byte[] getDataXYAsByte(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYAsByte(c);

        return null;
    }

    /**
     * Returns a direct reference to 1D byte array data [XY] for specified t, z, c
     */
    public short[] getDataXYAsShort(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYAsShort(c);

        return null;
    }

    /**
     * Returns a direct reference to 1D byte array data [XY] for specified t, z, c
     */
    public int[] getDataXYAsInt(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYAsInt(c);

        return null;
    }

    /**
     * Returns a direct reference to 1D byte array data [XY] for specified t, z, c
     */
    public float[] getDataXYAsFloat(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYAsFloat(c);

        return null;
    }

    /**
     * Returns a direct reference to 1D byte array data [XY] for specified t, z, c
     */
    public double[] getDataXYAsDouble(int t, int z, int c)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataXYAsDouble(c);

        return null;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public byte[][][] getDataXYZTAsByte(int c)
    {
        final int sizeT = getSizeT();
        final byte[][][] result = new byte[sizeT][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYZAsByte(t, c);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public short[][][] getDataXYZTAsShort(int c)
    {
        final int sizeT = getSizeT();
        final short[][][] result = new short[sizeT][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYZAsShort(t, c);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public int[][][] getDataXYZTAsInt(int c)
    {
        final int sizeT = getSizeT();
        final int[][][] result = new int[sizeT][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYZAsInt(t, c);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public float[][][] getDataXYZTAsFloat(int c)
    {
        final int sizeT = getSizeT();
        final float[][][] result = new float[sizeT][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYZAsFloat(t, c);

        return result;
    }

    /**
     * Returns a direct reference to 3D byte array data [T][Z][XY] for specified c
     */
    public double[][][] getDataXYZTAsDouble(int c)
    {
        final int sizeT = getSizeT();
        final double[][][] result = new double[sizeT][][];

        for (int t = 0; t < sizeT; t++)
            result[t] = getDataXYZAsDouble(t, c);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public byte[][] getDataXYZAsByte(int t, int c)
    {
        final int sizeZ = getSizeZ(t);
        final byte[][] result = new byte[sizeZ][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYAsByte(t, z, c);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public short[][] getDataXYZAsShort(int t, int c)
    {
        final int sizeZ = getSizeZ(t);
        final short[][] result = new short[sizeZ][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYAsShort(t, z, c);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public int[][] getDataXYZAsInt(int t, int c)
    {
        final int sizeZ = getSizeZ(t);
        final int[][] result = new int[sizeZ][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYAsInt(t, z, c);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public float[][] getDataXYZAsFloat(int t, int c)
    {
        final int sizeZ = getSizeZ(t);
        final float[][] result = new float[sizeZ][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYAsFloat(t, z, c);

        return result;
    }

    /**
     * Returns a direct reference to 2D byte array data [Z][XY] for specified t, c
     */
    public double[][] getDataXYZAsDouble(int t, int c)
    {
        final int sizeZ = getSizeZ(t);
        final double[][] result = new double[sizeZ][];

        for (int z = 0; z < sizeZ; z++)
            result[z] = getDataXYAsDouble(t, z, c);

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public byte[] getDataCopyXYCZTAsByte()
    {
        return getDataCopyXYCZTAsByte(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYCZTAsByte(byte[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYCZAsByte(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public short[] getDataCopyXYCZTAsShort()
    {
        return getDataCopyXYCZTAsShort(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYCZTAsShort(short[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYCZAsShort(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public int[] getDataCopyXYCZTAsInt()
    {
        return getDataCopyXYCZTAsInt(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYCZTAsInt(int[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYCZAsInt(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public float[] getDataCopyXYCZTAsFloat()
    {
        return getDataCopyXYCZTAsFloat(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYCZTAsFloat(float[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYCZAsFloat(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]
     */
    public double[] getDataCopyXYCZTAsDouble()
    {
        return getDataCopyXYCZTAsDouble(null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYCZTAsDouble(double[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYCZAsDouble(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public byte[] getDataCopyXYCZAsByte(int t)
    {
        return getDataCopyXYCZAsByte(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYCZAsByte(int t, byte[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYCAsByte(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public short[] getDataCopyXYCZAsShort(int t)
    {
        return getDataCopyXYCZAsShort(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYCZAsShort(int t, short[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYCAsShort(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public int[] getDataCopyXYCZAsInt(int t)
    {
        return getDataCopyXYCZAsInt(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYCZAsInt(int t, int[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYCAsInt(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public float[] getDataCopyXYCZAsFloat(int t)
    {
        return getDataCopyXYCZAsFloat(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYCZAsFloat(int t, float[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYCAsFloat(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public double[] getDataCopyXYCZAsDouble(int t)
    {
        return getDataCopyXYCZAsDouble(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYCZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYCZAsDouble(int t, double[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYCAsDouble(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public byte[] getDataCopyXYCAsByte(int t, int z)
    {
        return getDataCopyXYCAsByte(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYCAsByte(int t, int z, byte[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYCAsByte(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public short[] getDataCopyXYCAsShort(int t, int z)
    {
        return getDataCopyXYCAsShort(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYCAsShort(int t, int z, short[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYCAsShort(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public int[] getDataCopyXYCAsInt(int t, int z)
    {
        return getDataCopyXYCAsInt(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYCAsInt(int t, int z, int[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYCAsInt(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public float[] getDataCopyXYCAsFloat(int t, int z)
    {
        return getDataCopyXYCAsFloat(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYCAsFloat(int t, int z, float[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYCAsFloat(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z
     */
    public double[] getDataCopyXYCAsDouble(int t, int z)
    {
        return getDataCopyXYCAsDouble(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYC] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYCAsDouble(int t, int z, double[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYCAsDouble(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public byte[] getDataCopyXYAsByte(int t, int z, int c)
    {
        return getDataCopyXYAsByte(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYAsByte(int t, int z, int c, byte[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYAsByte(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public short[] getDataCopyXYAsShort(int t, int z, int c)
    {
        return getDataCopyXYAsShort(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYAsShort(int t, int z, int c, short[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYAsShort(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public int[] getDataCopyXYAsInt(int t, int z, int c)
    {
        return getDataCopyXYAsInt(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYAsInt(int t, int z, int c, int[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYAsInt(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public float[] getDataCopyXYAsFloat(int t, int z, int c)
    {
        return getDataCopyXYAsFloat(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYAsFloat(int t, int z, int c, float[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYAsFloat(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c
     */
    public double[] getDataCopyXYAsDouble(int t, int z, int c)
    {
        return getDataCopyXYAsDouble(t, z, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XY] of internal 1D array data [XY] for specified t, z, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYAsDouble(int t, int z, int c, double[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyXYAsDouble(c, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public byte[] getDataCopyCXYZTAsByte()
    {
        return getDataCopyCXYZTAsByte(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyCXYZTAsByte(byte[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyCXYZAsByte(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public short[] getDataCopyCXYZTAsShort()
    {
        return getDataCopyCXYZTAsShort(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyCXYZTAsShort(short[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyCXYZAsShort(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public int[] getDataCopyCXYZTAsInt()
    {
        return getDataCopyCXYZTAsInt(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyCXYZTAsInt(int[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyCXYZAsInt(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public float[] getDataCopyCXYZTAsFloat()
    {
        return getDataCopyCXYZTAsFloat(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyCXYZTAsFloat(float[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyCXYZAsFloat(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]
     */
    public double[] getDataCopyCXYZTAsDouble()
    {
        return getDataCopyCXYZTAsDouble(null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZT] of internal 4D array data [T][Z][C][XY]<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyCXYZTAsDouble(double[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyCXYZAsDouble(t, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public byte[] getDataCopyCXYZAsByte(int t)
    {
        return getDataCopyCXYZAsByte(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyCXYZAsByte(int t, byte[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyCXYAsByte(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public short[] getDataCopyCXYZAsShort(int t)
    {
        return getDataCopyCXYZAsShort(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyCXYZAsShort(int t, short[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyCXYAsShort(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public int[] getDataCopyCXYZAsInt(int t)
    {
        return getDataCopyCXYZAsInt(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyCXYZAsInt(int t, int[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyCXYAsInt(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public float[] getDataCopyCXYZAsFloat(int t)
    {
        return getDataCopyCXYZAsFloat(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyCXYZAsFloat(int t, float[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyCXYAsFloat(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t
     */
    public double[] getDataCopyCXYZAsDouble(int t)
    {
        return getDataCopyCXYZAsDouble(t, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXYZ] of internal 3D array data [Z][C][XY] for specified t<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyCXYZAsDouble(int t, double[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeC();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyCXYAsDouble(t, z, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public byte[] getDataCopyCXYAsByte(int t, int z)
    {
        return getDataCopyCXYAsByte(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyCXYAsByte(int t, int z, byte[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXYAsByte(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public short[] getDataCopyCXYAsShort(int t, int z)
    {
        return getDataCopyCXYAsShort(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyCXYAsShort(int t, int z, short[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXYAsShort(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public int[] getDataCopyCXYAsInt(int t, int z)
    {
        return getDataCopyCXYAsInt(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyCXYAsInt(int t, int z, int[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXYAsInt(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public float[] getDataCopyCXYAsFloat(int t, int z)
    {
        return getDataCopyCXYAsFloat(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyCXYAsFloat(int t, int z, float[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXYAsFloat(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z
     */
    public double[] getDataCopyCXYAsDouble(int t, int z)
    {
        return getDataCopyCXYAsDouble(t, z, null, 0);
    }

    /**
     * Returns a 1D array data copy [CXY] of internal 2D array data [C][XY] for specified t, z<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyCXYAsDouble(int t, int z, double[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCXYAsDouble(out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public byte[] getDataCopyCAsByte(int t, int z, int x, int y)
    {
        return getDataCopyCAsByte(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyCAsByte(int t, int z, int x, int y, byte[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCAsByte(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public short[] getDataCopyCAsShort(int t, int z, int x, int y)
    {
        return getDataCopyCAsShort(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyCAsShort(int t, int z, int x, int y, short[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCAsShort(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public int[] getDataCopyCAsInt(int t, int z, int x, int y)
    {
        return getDataCopyCAsInt(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyCAsInt(int t, int z, int x, int y, int[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCAsInt(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public float[] getDataCopyCAsFloat(int t, int z, int x, int y)
    {
        return getDataCopyCAsFloat(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyCAsFloat(int t, int z, int x, int y, float[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCAsFloat(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y
     */
    public double[] getDataCopyCAsDouble(int t, int z, int x, int y)
    {
        return getDataCopyCAsDouble(t, z, x, y, null, 0);
    }

    /**
     * Returns a 1D array data copy [C] of specified t, z, x, y<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyCAsDouble(int t, int z, int x, int y, double[] out, int off)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            return img.getDataCopyCAsDouble(x, y, out, off);

        return out;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public byte[] getDataCopyXYZTAsByte(int c)
    {
        return getDataCopyXYZTAsByte(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYZTAsByte(int c, byte[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYZAsByte(t, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public short[] getDataCopyXYZTAsShort(int c)
    {
        return getDataCopyXYZTAsShort(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYZTAsShort(int c, short[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYZAsShort(t, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public int[] getDataCopyXYZTAsInt(int c)
    {
        return getDataCopyXYZTAsInt(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYZTAsInt(int c, int[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYZAsInt(t, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public float[] getDataCopyXYZTAsFloat(int c)
    {
        return getDataCopyXYZTAsFloat(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYZTAsFloat(int c, float[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYZAsFloat(t, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c
     */
    public double[] getDataCopyXYZTAsDouble(int c)
    {
        return getDataCopyXYZTAsDouble(c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZT] of internal 3D array data [T][Z][XY] for specified c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYZTAsDouble(int c, double[] out, int off)
    {
        final long sizeT = getSizeT();
        final long len = (long) getSizeX() * (long) getSizeY() * (long) getSizeZ();
        if ((len * sizeT) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeT));
        int offset = off;

        for (int t = 0; t < sizeT; t++)
        {
            getDataCopyXYZAsDouble(t, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public byte[] getDataCopyXYZAsByte(int t, int c)
    {
        return getDataCopyXYZAsByte(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public byte[] getDataCopyXYZAsByte(int t, int c, byte[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final byte[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYAsByte(t, z, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public short[] getDataCopyXYZAsShort(int t, int c)
    {
        return getDataCopyXYZAsShort(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public short[] getDataCopyXYZAsShort(int t, int c, short[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final short[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYAsShort(t, z, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public int[] getDataCopyXYZAsInt(int t, int c)
    {
        return getDataCopyXYZAsInt(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public int[] getDataCopyXYZAsInt(int t, int c, int[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final int[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYAsInt(t, z, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public float[] getDataCopyXYZAsFloat(int t, int c)
    {
        return getDataCopyXYZAsFloat(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public float[] getDataCopyXYZAsFloat(int t, int c, float[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final float[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYAsFloat(t, z, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c
     */
    public double[] getDataCopyXYZAsDouble(int t, int c)
    {
        return getDataCopyXYZAsDouble(t, c, null, 0);
    }

    /**
     * Returns a 1D array data copy [XYZ] of internal 2D array data [Z][XY] for specified t, c<br>
     * If (out != null) then it's used to store result at the specified offset
     */
    public double[] getDataCopyXYZAsDouble(int t, int c, double[] out, int off)
    {
        final long sizeZ = getSizeZ();
        final long len = (long) getSizeX() * (long) getSizeY();
        if ((len * sizeZ) >= Integer.MAX_VALUE)
            throw new TooLargeArrayException();

        final double[] result = Array1DUtil.allocIfNull(out, (int) (len * sizeZ));
        int offset = off;

        for (int z = 0; z < sizeZ; z++)
        {
            getDataCopyXYAsDouble(t, z, c, result, offset);
            offset += len;
        }

        return result;
    }

    /**
     * Sets 1D array data [XY] for specified t, z, c
     */
    public void setDataXY(int t, int z, int c, Object value)
    {
        final IcyBufferedImage img = getImage(t, z);

        if (img != null)
            img.setDataXY(c, value);
    }

    /**
     * @deprecated Uses {@link SequenceUtil#getSubSequence(Sequence, int, int, int, int, int, int, int, int)} instead.
     */
    @Deprecated
    public Sequence getSubSequence(int startX, int startY, int startZ, int startT, int sizeX, int sizeY, int sizeZ,
            int sizeT)
    {
        return SequenceUtil.getSubSequence(this, startX, startY, startZ, startT, sizeX, sizeY, sizeZ, sizeT);
    }

    /**
     * @deprecated Use {@link SequenceUtil#getCopy(Sequence)} instead.
     */
    @Deprecated
    public Sequence getCopy()
    {
        return SequenceUtil.getCopy(this);
    }

    /**
     * Set all viewer containing this sequence to time t.
     * 
     * @deprecated Use this piece of code instead :<br>
     *             <code>for(Viewer v: Icy.getMainInterface().getViewers(sequence))</code></br>
     *             <code>   v.setT(...)</code>
     */
    @Deprecated
    public void setT(int t)
    {
        for (Viewer viewer : Icy.getMainInterface().getViewers())
            if (viewer.getSequence() == this)
                viewer.setT(t);
    }

    /**
     * Load XML persistent data from file.<br>
     * This method should only be called once when the sequence has just be loaded from file.<br>
     * Note that it uses {@link #getFilename()} to define the XML filename so be sure that it is correctly filled before
     * calling this method.
     * 
     * @return <code>true</code> if XML data has been correctly loaded, <code>false</code> otherwise.
     */
    public boolean loadXMLData()
    {
        return persistent.loadXMLData();
    }

    /**
     * Synchronize XML data with sequence data :<br>
     * This function refresh all the meta data and ROIs of the sequence and put it in the current
     * XML document.
     */
    public void refreshXMLData()
    {
        persistent.refreshXMLData();
    }

    /**
     * Save attached XML data.
     */
    public boolean saveXMLData()
    {
        Exception exc = null;
        int retry = 0;

        // definitely ugly but the XML parser may throw some exception in multi thread environnement
        // and we really don't want to lost the sequence metadata !
        while (retry < 5)
        {
            try
            {
                return persistent.saveXMLData();
            }
            catch (Exception e)
            {
                exc = e;
            }

            retry++;
        }

        System.err.println("Error while saving Sequence XML persistent data :");
        IcyExceptionHandler.showErrorMessage(exc, true);

        return false;
    }

    /**
     * Returns true if the specified XML data node exist
     * 
     * @param name
     *        name of node
     * @see #getNode(String)
     */
    public Node isNodeExisting(String name)
    {
        return persistent.getNode(name);
    }

    /**
     * Get XML data node identified by specified name.<br>
     * The node is created if needed.</br>
     * Note that the following node names are reserved: <i>image, name, meta, rois, lut</i></br>
     * 
     * @param name
     *        name of wanted node
     * @see #isNodeExisting(String)
     */
    public Node getNode(String name)
    {
        final Node result = persistent.getNode(name);

        if (result == null)
            return persistent.setNode(name);

        return result;
    }

    /**
     * @deprecated Use {@link #getNode(String)} instead.
     */
    @Deprecated
    public Node setNode(String name)
    {
        return persistent.setNode(name);
    }

    @Override
    public String toString()
    {
        return "Sequence: " + getName() + " - " + getSizeX() + " x " + getSizeY() + " x " + getSizeZ() + " x "
                + getSizeT() + " - " + getSizeC() + " ch (" + getDataType_() + ")";
    }

    /**
     * Do common job on "image add" here
     * 
     * @param image
     */
    public void onImageAdded(IcyBufferedImage image)
    {
        // colorModel not yet defined ?
        if (colorModel == null)
            // define it from the image colorModel
            setColorModel(IcyColorModel.createInstance(image.getIcyColorModel(), true, true));

        // add listener to image
        image.addListener(this);

        // notify changed
        dataChanged(image, SequenceEventType.ADDED);
    }

    /**
     * Do common job on "image replaced" here
     */
    public void onImageReplaced(IcyBufferedImage oldImage, IcyBufferedImage newImage)
    {
        // we replaced the only present image
        final boolean typeChange = getNumImage() == 1;

        beginUpdate();
        try
        {
            if (typeChange)
            {
                // colorModel not compatible ?
                if (!colorModel.isCompatible(newImage.getIcyColorModel()))
                    // define it from the new image colorModel
                    setColorModel(IcyColorModel.createInstance(newImage.getIcyColorModel(), true, true));
                // only inform about a type change if sequence sizeX and sizeY changed
                else if ((oldImage.getSizeX() != newImage.getSizeX()) || (oldImage.getSizeY() != newImage.getSizeY()))
                    typeChanged();
            }

            // TODO: improve cleaning here
            // need that to avoid memory leak as we manually patch the image colorspace
            if (colorModel != null)
                colorModel.getIcyColorSpace().removeListener(oldImage.getIcyColorModel());
            // remove listener from old image
            oldImage.removeListener(this);
            // notify about old image remove
            dataChanged(oldImage, SequenceEventType.REMOVED);

            // add listener to new image
            newImage.addListener(this);
            // notify about new image added
            dataChanged(newImage, SequenceEventType.ADDED);
        }
        finally
        {
            endUpdate();
        }
    }

    /**
     * Do common job on "image remove" here
     * 
     * @param image
     */
    public void onImageRemoved(IcyBufferedImage image)
    {
        // no more image ? --> releasethe global colorModel
        if (isEmpty())
            setColorModel(null);

        // TODO: improve cleaning here
        // need that to avoid memory leak as we manually patch the image colorspace
        if (colorModel != null)
            colorModel.getIcyColorSpace().removeListener(image.getIcyColorModel());
        // remove listener from image
        image.removeListener(this);

        // notify changed
        dataChanged(image, SequenceEventType.REMOVED);
    }

    /**
     * fire change event
     */
    @SuppressWarnings("deprecation")
    protected void fireChangedEvent(SequenceEvent e)
    {
        final List<SequenceListener> cachedListeners = new ArrayList<SequenceListener>(listeners);

        for (SequenceListener listener : cachedListeners)
            listener.sequenceChanged(e);

        // provide backward compatibility for painter
        if (e.getSourceType() == SequenceEventSourceType.SEQUENCE_OVERLAY)
        {
            final Painter painter;

            if (e.getSource() instanceof OverlayWrapper)
                painter = ((OverlayWrapper) e.getSource()).getPainter();
            else
                painter = (Painter) e.getSource();

            final SequenceEvent event = new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_PAINTER, painter,
                    e.getType(), e.getParam());

            for (SequenceListener listener : cachedListeners)
                listener.sequenceChanged(event);
        }
    }

    /**
     * fire close event
     */
    protected void fireClosedEvent()
    {
        for (SequenceListener listener : new ArrayList<SequenceListener>(listeners))
            listener.sequenceClosed(this);
    }

    /**
     * fire model image changed event
     */
    @Override
    public void fireModelImageChangedEvent()
    {
        for (SequenceModelListener listener : new ArrayList<SequenceModelListener>(modelListeners))
            listener.imageChanged();
    }

    /**
     * fire model dimension changed event
     */
    @Override
    public void fireModelDimensionChangedEvent()
    {
        for (SequenceModelListener listener : new ArrayList<SequenceModelListener>(modelListeners))
            listener.dimensionChanged();
    }

    public void beginUpdate()
    {
        updater.beginUpdate();
    }

    public void endUpdate()
    {
        updater.endUpdate();

        // no more updating
        if (!updater.isUpdating())
        {
            // lazy channel bounds update
            if (channelBoundsInvalid)
            {
                channelBoundsInvalid = false;
                // images channels bounds are valid at this point
                internalUpdateChannelsBounds();
            }
        }
    }

    public boolean isUpdating()
    {
        return updater.isUpdating();
    }

    /**
     * sequence meta has changed
     */
    public void metaChanged(String metaName)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_META, metaName));
    }

    /**
     * sequence meta has changed
     */
    public void metaChanged(String metaName, int param)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_META, metaName, null, param));
    }

    /**
     * sequence type (colorModel, size) changed
     */
    protected void typeChanged()
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_TYPE));
    }

    /**
     * sequence colorMap changed
     */
    protected void colormapChanged(IcyColorModel colorModel, int component)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_COLORMAP, colorModel, component));
    }

    /**
     * sequence component bounds changed
     */
    protected void componentBoundsChanged(IcyColorModel colorModel, int component)
    {
        updater.changed(
                new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_COMPONENTBOUNDS, colorModel, component));
    }

    // /**
    // * @deprecated Use {@link #overlayChanged(Overlay, SequenceEventType)} instead.
    // */
    // @Deprecated
    // private void painterChanged(Painter painter, SequenceEventType type)
    // {
    // updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_PAINTER, painter,
    // type));
    // }

    /**
     * @deprecated Use {@link #overlayChanged(Overlay)} instead.
     */
    @Deprecated
    public void painterChanged(Painter painter)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_OVERLAY, getOverlay(painter),
                SequenceEventType.CHANGED));
        // painterChanged(painter, SequenceEventType.CHANGED);
    }

    /**
     * overlay painter has changed
     */
    protected void overlayChanged(Overlay overlay, SequenceEventType type)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_OVERLAY, overlay, type));
    }

    /**
     * Notify specified painter of overlay has changed (the sequence should contains the specified
     * Overlay)
     */
    public void overlayChanged(Overlay overlay)
    {
        if (contains(overlay))
            overlayChanged(overlay, SequenceEventType.CHANGED);
    }

    /**
     * Called when an overlay has changed (internal method).<br>
     * Use {@link #overlayChanged(Overlay)} instead.
     */
    @Override
    public void overlayChanged(OverlayEvent event)
    {
        // only take care about overlay painter change here (need redraw)
        if (event.getType() == OverlayEventType.PAINTER_CHANGED)
            overlayChanged(event.getSource(), SequenceEventType.CHANGED);
    }

    /**
     * @deprecated Use {@link #roiChanged(ROI)} method instead.
     */
    @Deprecated
    public void roiChanged()
    {
        final Iterator<ROI> it = rois.iterator();

        // send a event for all ROI
        while (it.hasNext())
            roiChanged(it.next(), SequenceEventType.CHANGED);
    }

    /**
     * Notify specified roi has changed (the sequence should contains the specified ROI)
     */
    public void roiChanged(ROI roi)
    {
        if (contains(roi))
            roiChanged(roi, SequenceEventType.CHANGED);
    }

    /**
     * Notify specified roi has changed
     */
    protected void roiChanged(ROI roi, SequenceEventType type)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_ROI, roi, type));
    }

    /**
     * Data has changed (global change)<br>
     * Be careful, this implies all component bounds are recalculated, can be heavy !
     */
    public void dataChanged()
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_DATA, null));
    }

    /**
     * data has changed
     */
    protected void dataChanged(IcyBufferedImage image, SequenceEventType type)
    {
        updater.changed(new SequenceEvent(this, SequenceEventSourceType.SEQUENCE_DATA, image, type, 0));
    }

    @Override
    public void colorModelChanged(IcyColorModelEvent e)
    {
        switch (e.getType())
        {
            case COLORMAP_CHANGED:
                colormapChanged(e.getColorModel(), e.getComponent());
                break;

            case SCALER_CHANGED:
                componentBoundsChanged(e.getColorModel(), e.getComponent());
                break;
        }
    }

    @Override
    public void imageChanged(IcyBufferedImageEvent e)
    {
        final IcyBufferedImage image = e.getImage();

        switch (e.getType())
        {
            case BOUNDS_CHANGED:
                // update sequence channel bounds
                if (autoUpdateChannelBounds)
                {
                    // updating sequence ? delay update
                    if (isUpdating())
                        channelBoundsInvalid = true;
                    else
                        // refresh sequence channel bounds from images bounds
                        internalUpdateChannelsBounds();
                }
                break;

            case COLORMAP_CHANGED:
                // ignore that, we don't care about image colormap
                break;

            case DATA_CHANGED:
                // image data changed
                dataChanged(image, SequenceEventType.CHANGED);
                break;
        }
    }

    @Override
    public void roiChanged(ROIEvent event)
    {
        // notify the ROI has changed
        roiChanged(event.getSource(), SequenceEventType.CHANGED);
    }

    /**
     * process on sequence change
     */
    @Override
    public void onChanged(CollapsibleEvent e)
    {
        final SequenceEvent event = (SequenceEvent) e;

        switch (event.getSourceType())
        {
            // do here global process on sequence data change
            case SEQUENCE_DATA:
                // automatic channel bounds update enabled
                if (autoUpdateChannelBounds)
                {
                    // generic CHANGED event
                    if (event.getSource() == null)
                        // recalculate all images bounds (automatically update sequence bounds in imageChange event)
                        recalculateAllImageChannelsBounds();

                    // refresh sequence channel bounds from images bounds
                    internalUpdateChannelsBounds();
                }

                // fire SequenceModel event
                fireModelImageChangedEvent();
                break;

            // do here global process on sequence type change
            case SEQUENCE_TYPE:
                // fire SequenceModel event
                fireModelDimensionChangedEvent();
                break;

            // do here global process on sequence colormap change
            case SEQUENCE_COLORMAP:
                break;

            // do here global process on sequence component bounds change
            case SEQUENCE_COMPONENTBOUNDS:
                break;

            // do here global process on sequence overlay change
            case SEQUENCE_OVERLAY:
                break;

            // do here global process on sequence ROI change
            case SEQUENCE_ROI:
                break;
        }

        // notify listener we have changed
        fireChangedEvent(event);
    }

}
