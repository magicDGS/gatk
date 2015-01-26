package org.broadinstitute.hellbender.tools;

import htsjdk.samtools.*;
import htsjdk.samtools.util.CloserUtil;
import org.broadinstitute.hellbender.cmdline.*;
import org.broadinstitute.hellbender.cmdline.programgroups.ReadProgramGroup;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadWalker;
import org.broadinstitute.hellbender.engine.ReferenceContext;

import java.io.File;
import java.util.Optional;

@CommandLineProgramProperties(
	usage = "Prints reads from the input to the output.",
    usageShort = "Print reads",
    programGroup = ReadProgramGroup.class
)
public class PrintReads extends ReadWalker {

    @Argument(fullName = "output", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, doc="Write output to this file")
    public File OUTPUT;

    private SAMFileWriter outputWriter;

    @Override
    public void onTraversalStart() {
        final SAMFileHeader outputHeader = getHeaderForReads().clone();
        outputWriter = new SAMFileWriterFactory().makeWriter(outputHeader, true, OUTPUT, REFERENCE_FILE);
    }

    @Override
    public void apply( SAMRecord read, Optional<ReferenceContext> referenceContext, Optional<FeatureContext> featureContext ) {
        outputWriter.addAlignment(read);
    }

    @Override
    public Object onTraversalDone() {
        CloserUtil.close(outputWriter);
        return null;
    }
}
