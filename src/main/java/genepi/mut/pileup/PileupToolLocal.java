package genepi.mut.pileup;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import genepi.base.Tool;
import genepi.io.FileUtil;
import genepi.io.text.LineWriter;
import genepi.mut.objects.BasePosition;
import genepi.mut.objects.VariantLine;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;

public class PileupToolLocal extends Tool {

	public PileupToolLocal(String[] args) {
		super(args);
	}

	@Override
	public void createParameters() {

		addParameter("input", "input folder", Tool.STRING);
		addParameter("output", "output folder", Tool.STRING);
		addParameter("reference", "reference as fasta", Tool.STRING);
		addOptionalParameter("indel", "call indels?", Tool.STRING);
	}

	@Override
	public void init() {
		System.out.println("Execute CNV-Server locally \n");
	}

	@Override
	public int run() {

		String input = (String) getValue("input");

		String output = (String) getValue("output");

		String indel = (String) getValue("indel");

		String refPath = (String) getValue("reference");

		File folderIn = new File(input);

		File folderOut = new File(output);

		if (!folderIn.exists() || !folderOut.exists()) {

			System.out.println("Please check if input/output folders exist");
			System.out.println("input: " + folderIn.getAbsolutePath());
			System.out.println("output: " + folderOut.getAbsolutePath());
			return 0;
		}

		File[] files = folderIn.listFiles();

		for (File file : files) {

			long start = System.currentTimeMillis();

			System.out.println(" Processing: " + file.getName());

			BamAnalyser analyser = new BamAnalyser(file.getName(), refPath);

			try {

				analyseReads(file, analyser);

				String outputPath = FileUtil.path(output, file.getName().substring(0, file.getName().lastIndexOf(".")));

				determineVariants(analyser, outputPath, Boolean.valueOf(indel));

			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			System.out.println("Fin. Took " + (System.currentTimeMillis() - start) / 1000 + " sec");

		}

		return 0;
	}

	// mapper
	private void analyseReads(File file, BamAnalyser analyser) throws Exception, IOException {

		// TODO double check if primary and secondary alignment is used for
		// CNV-Server
		final SamReader reader = SamReaderFactory.makeDefault().validationStringency(ValidationStringency.LENIENT)
				.open(file);

		SAMRecordIterator fileIterator = reader.iterator();

		while (fileIterator.hasNext()) {

			SAMRecord record = fileIterator.next();

			analyser.analyseRead(record);

		}
		reader.close();
	}

	// reducer
	private void determineVariants(BamAnalyser analyser, String output, boolean indel) throws IOException {

		String outputFiltered = output + "_filtered.txt";

		LineWriter writer = new LineWriter(outputFiltered);

		writer.write(
				"SampleID\tPos\tRef\tVariant\tMajor/Minor\tVariant-Level\tCoverage-FWD\tCoverage-Rev\tCoverage-Total");

		HashMap<String, BasePosition> counts = analyser.getCounts();

		String reference = analyser.getReferenceString();

		for (String key : counts.keySet()) {

			String id = key.split(":")[0];

			int pos = Integer.valueOf(key.split(":")[1]);

			if (pos > 0 && pos <= reference.length()) {

				BasePosition basePos = counts.get(key);

				basePos.setId(id);

				basePos.setPos(pos);

				char ref = reference.charAt(pos - 1);

				VariantLine line = new VariantLine();

				line.setCallDel(indel);

				line.setRef(ref);

				line.analysePosition(basePos);

				line.callVariants();

				if (line.isFinalVariant()) {
					writer.write(line.writeVariant());
				}

			}

		}
		writer.close();
	}

	public static void main(String[] args) {

		String input = "test-data/mtdna/bam/input/";

		PileupToolLocal pileup = new PileupToolLocal(new String[] { "--input", input, "--reference",
				"/home/seb/Desktop/rcrs/rCRS.fasta", "--output", "testdata/tmp/", "--indel", "true" });

		pileup.start();

	}

}