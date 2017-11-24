package genepi.mut.pileup;

import genepi.hadoop.CacheStore;
import genepi.hadoop.PreferenceStore;
import genepi.mut.objects.BasePosition;
import genepi.mut.util.BaqAlt;
import genepi.mut.util.ReferenceUtil;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.reference.FastaSequenceIndex;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.broadinstitute.gatk.utils.baq.BAQ;
import org.seqdoop.hadoop_bam.FileVirtualSplit;
import org.seqdoop.hadoop_bam.SAMRecordWritable;

public class PileupMapper extends Mapper<LongWritable, SAMRecordWritable, Text, BasePosition> {

	private Text outKey = new Text();
	int mapQual;
	int baseQual;
	int alignQual;
	boolean baq;
	String filename;
	String referenceName;
	IndexedFastaSequenceFile refReader;

	BaqAlt baqHMMAltered;
	BAQ baqHMM;
	String version;
	int length;

	HashMap<String, BasePosition> counts;

	enum Counters {

		GOOD_MAPPING, BAD_MAPPING, BAD_QUALITY, GOOD_QUALITY, BAD_ALIGNMENT_SCORE, WRONG_REF, INVALID_READ, INVALID_FLAGS

	}

	enum versionEnum {

		MTDNA, GENOME

	}

	protected void setup(Context context) throws IOException, InterruptedException {

		mapQual = context.getConfiguration().getInt("mapQual", 20);
		alignQual = context.getConfiguration().getInt("alignQual", 30);
		baseQual = context.getConfiguration().getInt("baseQual", 20);
		baq = context.getConfiguration().getBoolean("baq", true);

		// required for BAM splits
		if (context.getInputSplit().getClass().equals(FileVirtualSplit.class)) {
			filename = ((FileVirtualSplit) context.getInputSplit()).getPath().getName().replace(".bam", "");
		} else {
			filename = ((FileSplit) context.getInputSplit()).getPath().getName().replace(".bam", "").replace(".sam", "")
					.replace(".cram", "");
		}

		CacheStore cache = new CacheStore(context.getConfiguration());

		File referencePath = new File(cache.getArchive("reference"));

		String fastaPath = ReferenceUtil.findFileinDir(referencePath, ".fasta");
		String faiPath = ReferenceUtil.findFileinDir(referencePath, ".fasta.fai");

		length = (ReferenceUtil.readInReference(fastaPath)).length();
		counts = new HashMap<String, BasePosition>(length);

		PreferenceStore store = new PreferenceStore(context.getConfiguration());
		version = store.getString("server.version");

		refReader = new IndexedFastaSequenceFile(new File(fastaPath), new FastaSequenceIndex(new File(faiPath)));

		// defined by samtools mpileup: gap open prob (phred scale 40), gap
		// extension prob (phred scale 20)

		if (version.equalsIgnoreCase(versionEnum.MTDNA.name())) {
			baqHMMAltered = new BaqAlt(1e-4, 1e-2, 7, (byte) 0, true);
		} else {
			baqHMM = new BAQ(1e-4, 1e-2, 7, (byte) 0, true);
		}

		System.out.println("BAQ " + baq);
		System.out.println("Server-Version " + version);
		System.out.println("baseQual " + baseQual);
		System.out.println("mapQual " + mapQual);
		System.out.println("alignQual " + alignQual);

	}

	@Override
	protected void cleanup(Context context) throws IOException, InterruptedException {
		for (String pos : counts.keySet()) {
			BasePosition basePos = counts.get(pos);

			outKey.set(filename + ":" + pos);
			context.write(outKey, basePos);
		}

	}

	public void map(LongWritable key, SAMRecordWritable value, Context context)
			throws IOException, InterruptedException {
		try {

			analyseBam(context, value.get());

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void analyseBam(Context context, SAMRecord samRecord) throws Exception {

		context.getCounter("mtdna", "OVERALL-READS").increment(1);

		if (samRecord.getMappingQuality() < mapQual) {
			context.getCounter("mtdna", "FILTERED").increment(1);
			context.getCounter("mtdna", "BAD-MAPPING").increment(1);
			return;
		}

		if (samRecord.getReadUnmappedFlag()) {
			context.getCounter("mtdna", "FILTERED").increment(1);
			context.getCounter("mtdna", "UNMAPPED").increment(1);
			return;
		}

		if (samRecord.getDuplicateReadFlag()) {
			context.getCounter("mtdna", "FILTERED").increment(1);
			context.getCounter("mtdna", "DUPLICATE").increment(1);
			return;
		}

		if (samRecord.getReadLength() <= 25) {
			context.getCounter("mtdna", "FILTERED").increment(1);
			context.getCounter("mtdna", "SHORT-READ").increment(1);
			return;
		}

		if (ReferenceUtil.getTagFromSamRecord(samRecord.getAttributes(), "AS") < alignQual) {
			context.getCounter("mtdna", "FILTERED").increment(1);
			context.getCounter("mtdna", "BAD-ALIGNMENT").increment(1);
			return;
		}

		context.getCounter("mtdna", "UNFILTERED").increment(1);

		if (baq) {
			if (version.equalsIgnoreCase(versionEnum.MTDNA.name())) {
				baqHMMAltered.baqRead(samRecord, refReader,
						genepi.mut.util.BaqAlt.CalculationMode.CALCULATE_AS_NECESSARY,
						genepi.mut.util.BaqAlt.QualityMode.OVERWRITE_QUALS);
			} else {
				baqHMM.baqRead(samRecord, refReader,
						org.broadinstitute.gatk.utils.baq.BAQ.CalculationMode.CALCULATE_AS_NECESSARY,
						org.broadinstitute.gatk.utils.baq.BAQ.QualityMode.OVERWRITE_QUALS);
			}
		}

		String readString = samRecord.getReadString();

		for (int i = 0; i < readString.length(); i++) {

			int currentPos = samRecord.getReferencePositionAtReadPosition(i + 1);

			if (samRecord.getBaseQualities()[i] >= baseQual) {

				context.getCounter("mtdna", "GOOD-QUAL").increment(1);

				BasePosition basePos = counts.get(currentPos + "");

				if (basePos == null) {
					basePos = new BasePosition();
					counts.put(currentPos + "", basePos);
				}

				char base = readString.charAt(i);

				if ((samRecord.getFlags() & 0x10) == 0x10) {
					context.getCounter("mtdna", "REV-READ").increment(1);
					switch (base) {
					case 'A':
						basePos.addaRev(1);
						basePos.addaRevQ(samRecord.getBaseQualities()[i]);
						break;
					case 'C':
						basePos.addcRev(1);
						basePos.addcRevQ(samRecord.getBaseQualities()[i]);
						break;
					case 'G':
						basePos.addgRev(1);
						basePos.addgRevQ(samRecord.getBaseQualities()[i]);
						break;
					case 'T':
						basePos.addtRev(1);
						basePos.addtRevQ(samRecord.getBaseQualities()[i]);
						break;
					case 'N':
						basePos.addnRev(1);
						break;
					default:
						break;
					}
				} else {
					context.getCounter("mtdna", "FWD-READ").increment(1);
					switch (base) {
					case 'A':
						basePos.addaFor(1);
						basePos.addaForQ(samRecord.getBaseQualities()[i]);
						break;
					case 'C':
						basePos.addcFor(1);
						basePos.addcForQ(samRecord.getBaseQualities()[i]);
						break;
					case 'G':
						basePos.addgFor(1);
						basePos.addgForQ(samRecord.getBaseQualities()[i]);
						break;
					case 'T':
						basePos.addtFor(1);
						basePos.addtForQ(samRecord.getBaseQualities()[i]);
						break;
					case 'N':
						basePos.addnFor(1);
						break;
					default:
						break;
					}
				}

			} else {
				context.getCounter("mtdna", "BAD-QUAL").increment(1);
			}
		}

		/** for deletions */
		Integer currentReferencePos = samRecord.getAlignmentStart();
		for (CigarElement cigarElement : samRecord.getCigar().getCigarElements()) {
			int count = 0;
			if (cigarElement.getOperator() == CigarOperator.D) {

				Integer cigarElementStart = currentReferencePos;
				Integer cigarElementLength = cigarElement.getLength();
				Integer cigarElementEnd = currentReferencePos + cigarElementLength;

				while (cigarElementStart < cigarElementEnd) {

					BasePosition basePos = counts.get(cigarElementStart + "");

					if (basePos == null) {
						basePos = new BasePosition();
						counts.put(cigarElementStart + "", basePos);
					}

					if ((samRecord.getFlags() & 0x10) == 0x10) {
						basePos.adddRev(1);
						basePos.adddRevQ(samRecord.getBaseQualities()[count]);
					} else {
						basePos.adddFor(1);
						basePos.adddForQ(samRecord.getBaseQualities()[count]);
					}

					cigarElementStart++;
				}

			}

			if (cigarElement.getOperator().consumesReferenceBases()) {
				currentReferencePos = currentReferencePos + cigarElement.getLength();
			}
			count++;
		}
	}

	// needed for hg19 reference
	private int hg19Mapper(int pos) {

		int updatedPos = 0;
		if ((pos >= 315 && pos < 3107) || pos >= 16193) {
			updatedPos = pos - 2;
		} else if (pos >= 3107 && pos < 16193) {
			updatedPos = pos - 1;
		} else {
			updatedPos = pos;
		}
		return updatedPos;
	}

}