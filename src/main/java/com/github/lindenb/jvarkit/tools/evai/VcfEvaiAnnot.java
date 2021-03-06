/*
The MIT License (MIT)

Copyright (c) 2019 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


*/
package com.github.lindenb.jvarkit.tools.evai;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.beust.jcommander.Parameter;
import com.github.lindenb.jvarkit.lang.CharSplitter;
import com.github.lindenb.jvarkit.lang.StringUtils;
import com.github.lindenb.jvarkit.util.JVarkitVersion;
import com.github.lindenb.jvarkit.util.bio.DistanceParser;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.NoSplitter;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.log.ProgressFactory;

import htsjdk.samtools.util.CoordMath;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.Locatable;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.tribble.readers.TabixReader;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFIterator;

/**
BEGIN_DOC

For Adeline Goudal (master Bioinfo 2019) .

END_DOC
*/

@Program(name="vcfevaiannot",
	description="Annotate vcf with evai/intervar data.",
	keywords= {"vcf","annotation","evai","intervar"},
	creationDate = "20190507",
	modificationDate="20190507",
	generate_doc = false
	)
public class VcfEvaiAnnot extends Launcher {

	private static final Logger LOG = Logger.build(VcfEvaiAnnot.class).make();
	private static final String INTERVAR_COLUMN ="InterVar: InterVar and Evidence";
	private static final String EVAI_PFX ="EVAI_";
	private static final String INTERVAR_PFX ="INTERVAR_";

	
	@Parameter(names={"-o","--output"},description=OPT_OUPUT_FILE_OR_STDOUT)
	private File outputFile = null;
	@Parameter(names={"-T","--tabix"},description="File containing path to eVAI files. One file per line. Each file MUST be compressed and indexed with tabix.",required = true)
	private Path tabixListPath = null;
	@Parameter(names={"-B","--buffer-size"},description="Buffer size. "+DistanceParser.OPT_DESCRIPTION ,converter = DistanceParser.StringConverter.class,splitter = NoSplitter.class)
	private int buffer_size = 1_000;
	@Parameter(names={"--ignore-filtered"},description="Ignore FILTERed variants (should go faster)")
	private boolean ignore_filtered = false;

	
	private enum AnnoType {evai,intervar};
	
	private abstract class AbstractAnnoLine implements Locatable
		{
		final EvaiTabix owner;
		final String tokens[];
		AbstractAnnoLine(EvaiTabix owner,final String tokens[]) {
			this.owner = owner;
			this.tokens = tokens;
			}
		@Override
		public String getContig() {
			return tokens[0];
			}
		@Override
		public int getStart() {
			return Integer.parseInt(tokens[1]);
			}
		@Override
		public int getEnd() {
			return Integer.parseInt(tokens[2]);
			}
		@Override
		public String toString() {
			return String.join("\t", tokens);
			}
		abstract boolean match(final VariantContext ctx);
		}
	
	private class EvaiLine extends AbstractAnnoLine {
		EvaiLine(EvaiTabix owner,final String tokens[]) {
			super(owner,tokens);
			}
		
		@Override
		public int getStart() {
			return Integer.parseInt(tokens[1]) - (isDeletion()?1:0);
			}
		
		public boolean isDeletion() { return tokens[4].equals("-");}
		boolean match(final VariantContext ctx) {
			if(!this.getContig().equals(ctx.getContig())) return false;
			if(this.getStart()!=ctx.getStart()) return false;
			if(this.getEnd()!=ctx.getEnd()) return false;
			if(!ctx.getReference().getDisplayString().equals(tokens[3]) && !isDeletion()) return false;
			if(ctx.getNAlleles()==1) return false;
			if(!ctx.getAlleles().get(1).getDisplayString().equals(tokens[4]) && !isDeletion()) return false;
			return true;
			}
		}
	private class IntervarLine extends AbstractAnnoLine {
		IntervarLine(EvaiTabix owner,final String tokens[]) {
			super(owner,tokens);
			}
		boolean match(final VariantContext ctx) {
			if(!this.getContig().equals(ctx.getContig())) return false;
			if(this.getStart()!=ctx.getStart()) return false;
			if(this.getEnd()!=ctx.getEnd()) return false;
			if(!ctx.getReference().getDisplayString().equals(tokens[3]) ) return false;
			if(ctx.getNAlleles()==1) return false;
			if(!ctx.getAlleles().get(1).getDisplayString().equals(tokens[4])) return false;
			return true;
			}
	}
	
	private class EvaiTabix implements Closeable {
		final String filename;
		final TabixReader tabix;
		String sample = null;
		Interval lastInterval = null;
		final List<AbstractAnnoLine> buffer = new ArrayList<>();
		final Map<String,Integer> column2index = new HashMap<>();
		AnnoType type = null;
		
		EvaiTabix(final String filename) throws IOException {
			this.filename = filename;
			this.tabix = new TabixReader(filename);
			final String version_tag = "##eVAI-version=";//0.4.2
			final String sample_tag = "##SAMPLE-ID:";
			for(;;) {
				String line = this.tabix.readLine();
				if(line==null || !line.startsWith("#")) break;
				if(line.startsWith(version_tag)) {
					final String version = line.substring(version_tag.length()).trim();
					if(!version.equals("0.4.2")) throw new IOException("unknown vai version "+ line+" "+filename);
					}
				if(line.startsWith(sample_tag)) {
					this.sample = line.substring(sample_tag.length()).trim();
					}
				if(line.startsWith("#CHROM") || line.startsWith("#Chr")) {
					this.type = line.startsWith("#CHROM")?AnnoType.evai:AnnoType.intervar;
					final String tokens[] = CharSplitter.TAB.split(line);
					for(int i=0;i< tokens.length;++i) {
						this.column2index.put(tokens[i],i);
						}
					}
				
				}
			if(this.type==null) {
				throw new IOException("unknown file type  "+filename);
				}
			else if(this.type.equals(AnnoType.evai)) {
				if(StringUtils.isBlank(this.sample)) {
					throw new IOException("No sample found in "+this.filename);
					}
				if(this.column2index.isEmpty()) {
					throw new IOException("No header found in "+this.filename);
					}
				}
			else if(this.type.equals(AnnoType.intervar)) {
				if(!this.column2index.containsKey(INTERVAR_COLUMN)) {
					throw new IOException("In "+this.filename+" cannot get column "+INTERVAR_COLUMN);
					}
				}
			}
		
		
		
		Optional<AbstractAnnoLine> query(final VariantContext ctx) {
			if(this.lastInterval==null ||
				!this.lastInterval.getContig().equals(ctx.getContig()) ||
				!CoordMath.encloses(lastInterval.getStart(), lastInterval.getEnd(),
						ctx.getStart(),ctx.getEnd())) {
				
				buffer.clear();
				this.lastInterval = new Interval(ctx.getContig(),
						Math.max(1,ctx.getStart()-10),
						ctx.getEnd() + Math.max(1, buffer_size)
						);
				final TabixReader.Iterator iter = this.tabix.query(this.lastInterval.getContig(), this.lastInterval.getStart(), this.lastInterval.getEnd());
				final CharSplitter tab = CharSplitter.TAB;
				try {
					for(;;) {
						final String line = iter.next();
						if(line==null) break;
						if(this.type==AnnoType.evai) {
							final EvaiLine evai = new EvaiLine(this,tab.split(line));
							this.buffer.add(evai);
							}
						else
							{
							final IntervarLine evai = new IntervarLine(this,tab.split(line));
							this.buffer.add(evai);
							}
						}
					}
				catch(final IOException err) {
					throw new RuntimeIOException(err);
					}
				}
			return this.buffer.stream().filter(L->L.match(ctx)).findFirst();
			}
		
		@Override
		public void close(){
			this.tabix.close();
			}
		}

	private final List<EvaiTabix> all_evai = new ArrayList<>();
	private final List<EvaiTabix> all_intervar = new ArrayList<>();
	
	private boolean isEvaiBooleanField(final String T) {
		return T.startsWith("BP") || T.startsWith("BS") || 
                       T.startsWith("BA") || 
                       T.startsWith("PM") || 
		       T.startsWith("PP") ||  
                       T.startsWith("PS")|| 
                       T.startsWith("PV") ;
		}
	
	@Override
	protected int doVcfToVcf(final String inputName,final  VCFIterator iterin, final VariantContextWriter out) {
		final VCFHeader header0 = iterin.getHeader();
		final VCFHeader header2 = new VCFHeader(header0);
		
		final Set<VCFHeaderLine> meta = new HashSet<>();
		/* evai fields */
		this.all_evai.
			stream().
			flatMap(T->T.column2index.keySet().stream()).
			filter(T->isEvaiBooleanField(T)).
			map(T->new VCFInfoHeaderLine(EVAI_PFX+T,1,VCFHeaderLineType.Integer,T)).
			forEach(H->meta.add(H));
		meta.add(new VCFInfoHeaderLine(EVAI_PFX+"FINAL_CLASSIFICATION",1,VCFHeaderLineType.String,"FINAL_CLASSIFICATION"));
		
		meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"PVS1",1,VCFHeaderLineType.Integer,"From intervar"));
		for(int i=0;i< 5;i++) meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"PS"+i,1,VCFHeaderLineType.Integer,"From intervar"));
		for(int i=0;i< 7;i++) meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"PM"+i,1,VCFHeaderLineType.Integer,"From intervar"));
		for(int i=0;i< 6;i++) meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"PP"+i,1,VCFHeaderLineType.Integer,"From intervar"));
		meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"BA1",1,VCFHeaderLineType.Integer,"From intervar"));
		for(int i=0;i< 5;i++) meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"BS"+i,1,VCFHeaderLineType.Integer,"From intervar"));
		for(int i=0;i< 8;i++) meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"BP"+i,1,VCFHeaderLineType.Integer,"From intervar"));
		meta.add(new VCFInfoHeaderLine(INTERVAR_PFX+"_CLASSIFICATION",1,VCFHeaderLineType.String,"FINAL_CLASSIFICATION"));

		
		meta.stream().forEach(M->header2.addMetaDataLine(M));
		
		JVarkitVersion.getInstance().addMetaData(this, header2);
		final ProgressFactory.Watcher<VariantContext> progress = ProgressFactory.newInstance().dictionary(header0).logger(LOG).build();
		
		out.writeHeader(header2);
		while(iterin.hasNext()) {
			final VariantContext ctx = progress.apply(iterin.next());
			if(this.ignore_filtered && ctx.isFiltered()) {
				out.add(ctx);
				continue;
				}
			final Map<String, Object> attributes = new HashMap<>();
			
			/* evai */

				
			final Optional<AbstractAnnoLine> candidateEvai = this.all_evai.
					stream().
					map(T->T.query(ctx)).
					filter(T->T.isPresent()).
					map(T->T.get()).
					findFirst();
			if(candidateEvai.isPresent()) {
				final AbstractAnnoLine line = candidateEvai.get();
				for(final String column : line.owner.column2index.keySet()) {
					if(!isEvaiBooleanField(column)) continue;
					final int col_idx = line.owner.column2index.get(column);
					if(col_idx>= line.tokens.length) continue;
					final String valuestr=line.tokens[col_idx];
					if(valuestr.equals("n.a.")) continue;
					final Object value ;
					if(valuestr.equals("true")) value=1;
					else if(valuestr.equals("TRUE(STRONG)")) value=10;
					else if(valuestr.equals("false")) value=0;
					else throw new IllegalArgumentException(column+" "+valuestr);
					attributes.put(EVAI_PFX+column, value);
					}
					
				final int col_idx = line.owner.column2index.get("FINAL_CLASSIFICATION");
				if(col_idx>=0 && col_idx< line.tokens.length) {
					final String value = line.tokens[col_idx].replace(" ","_");
					if(value.equals("n.a.") ||StringUtils.isBlank(value)) {
						//ignore
						}
					attributes.put(EVAI_PFX+"FINAL_CLASSIFICATION", value);
					}
				}
			
			/* intervar */

			
			final Optional<AbstractAnnoLine> candidateIntervar = this.all_intervar.
					stream().
					map(T->T.query(ctx)).
					filter(T->T.isPresent()).
					map(T->T.get()).
					findFirst();
			if(candidateIntervar.isPresent()) {
				final AbstractAnnoLine line = candidateIntervar.get();
				final int col_idx = line.owner.column2index.get(INTERVAR_COLUMN);
				if(col_idx==-1) throw new IllegalArgumentException("Cannot find "+INTERVAR_COLUMN);
				String valuestr=line.tokens[col_idx];
				final String intervarKey1 = "InterVar: Uncertain significance ";
				final String intervarKey2 = "InterVar: Benign ";
				if(valuestr.startsWith(intervarKey1)) {
					valuestr = valuestr.substring(intervarKey1.length()).trim();
					attributes.put(INTERVAR_PFX+"_CLASSIFICATION", "uncertain_significance");
					}
				else if(valuestr.startsWith(intervarKey2)) {
					valuestr = valuestr.substring(intervarKey2.length()).trim();
					attributes.put(INTERVAR_PFX+"_CLASSIFICATION", "benign");
					}
				else
					{
					throw new IllegalArgumentException(valuestr);
					}
				valuestr=valuestr.replace(", ", ",");
				final String tokens[] = valuestr.split("[ ]+");
				for(String token:tokens) {
					if(StringUtils.isBlank(token)) continue;
					int eq=token.indexOf("=");
					if(eq==-1) throw new RuntimeException("= missing in "+candidateIntervar);
					final String key = token.substring(0,eq);
					if(token.charAt(eq+1)=='[')
						{
						token = token.substring(eq+2,token.length()-1);
						String nums[]=CharSplitter.COMMA.split(token);
						for(int x=0;x< nums.length;++x) {
							attributes.put(INTERVAR_PFX+key+(x),Integer.parseInt(nums[x]));
							}
						}
					else
						{
						attributes.put(INTERVAR_PFX+key,Integer.parseInt(token.substring(eq+1)));
						}
				
					}
				
				}
				
			final VariantContextBuilder vcb = new VariantContextBuilder(ctx);
			for(final String k: attributes.keySet()) vcb.attribute(k,attributes.get(k));
			out.add(vcb.make());
			}
		
		progress.close();
		return 0;
		}
	
	
	@Override
	public int doWork(final List<String> args) {
		try {
			Files.lines(this.tabixListPath).forEach(L->{
				try {
					final EvaiTabix t =new EvaiTabix(L);
					if(t.type.equals(AnnoType.evai))
						{
						this.all_evai.add(t);
						}
					else
						{
						this.all_intervar.add(t);
						}
					}
				catch(final IOException err) {
					throw new RuntimeIOException(err);
					}
				});
			
			return doVcfToVcf(args, this.outputFile);
			}
		catch(final Throwable err) {
			LOG.error(err);
			return -1;
			}
		finally {
			this.all_evai.stream().forEach(T->T.close());
			this.all_intervar.stream().forEach(T->T.close());
			}
		}
	
	public static void main(final String[] args) {
		new VcfEvaiAnnot().instanceMainWithExit(args);
		}
}