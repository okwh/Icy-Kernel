package plugins.kernel.roi.descriptor.basic;

import icy.roi.ROI;
import icy.roi.ROIDescriptor;
import icy.sequence.Sequence;
import icy.type.rectangle.Rectangle5D;

/**
 * Size C ROI descriptor class (see {@link ROIDescriptor})
 * 
 * @author Stephane
 */
public class SizeCROIDescriptor extends ROIDescriptor
{
    public static final String ID = "SizeC";

    public SizeCROIDescriptor()
    {
        super(ID, "Size C", Double.class);
    }

    @Override
    public String getDescription()
    {
        return "Size C";
    }

    @Override
    public Object compute(ROI roi, Sequence sequence) throws UnsupportedOperationException
    {
        return Double.valueOf(getSizeC(roi.getBounds5D()));
    }

    /**
     * Returns size C of specified Rectangle5D object
     */
    public static double getSizeC(Rectangle5D point)
    {
        if (point == null)
            return Double.NaN;

        return point.getSizeC();
    }
}
