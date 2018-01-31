/*
The MIT License (MIT)

Copyright (c) 2018 Pierre Lindenbaum

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


History:

*/
package com.github.lindenb.jvarkit.tools.burden;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import htsjdk.samtools.util.StringUtil;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;

import com.github.lindenb.jvarkit.util.vcf.VcfIterator;
import com.github.lindenb.jvarkit.util.vcf.predictions.AnnPredictionParser;
import com.github.lindenb.jvarkit.util.vcf.predictions.AnnPredictionParserFactory;
import com.github.lindenb.jvarkit.util.vcf.predictions.VepPredictionParser;
import com.github.lindenb.jvarkit.util.vcf.predictions.VepPredictionParserFactory;

import com.beust.jcommander.Parameter;
import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.math.stats.FisherExactTest;
import com.github.lindenb.jvarkit.util.Pedigree;
import com.github.lindenb.jvarkit.util.go.GoTree;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
/**

BEGIN_DOC


```make
all:dist/vcfburdengoenrichment.jar go.rdf.xml.gz goa.txt input.vcf input.ped
	java -jar $< \
		 --pedigree  $(word 5,$^) \
		 --go $(word 2,$^) \
		 --genes $(word 3,$^) \
		  $(word 4,$^)

go.rdf.xml.gz: 
	wget -O $@ "http://archive.geneontology.org/latest-termdb/go_daily-termdb.rdf-xml.gz"
goa.txt:
	wget -q -O - "http://cvsweb.geneontology.org/cgi-bin/cvsweb.cgi/go/gene-associations/gene_association.goa_human.gz?rev=HEAD" | gunzip -c | grep -v '^!' | cut -f3,5 | sort | uniq > $@
```
	

END_DOC
*/
@Program(
		name="vcfburdengoenrichment",
		description="",
		keywords={"gene","vcf","vep","go"},
		generate_doc=false
		)
public class VcfBurdenGoEnrichment
	extends Launcher
	{
	private static final Logger LOG = Logger.build(VcfBurdenGoEnrichment.class).make();

	@Parameter(names={"-o","--output"},description=OPT_OUPUT_FILE_OR_STDOUT)
	private File outputFile = null;
	@Parameter(names={"-go","--go"},description=GoTree.GO_URL_OPT_DESC)
	private String goUri = GoTree.GO_RDF_URL;
	@Parameter(names={"-G","--genes"},description="Gene association file: tab delimited, two columns: (1) gene name (2) GO term ACN .",required=true)
	private File geneFile = null;
	@Parameter(names={"-p","--pedigree"},description=Pedigree.OPT_DESCRIPTION)
	private File pedFile = null;


	
	
	private class Node
		{
		final GoTree.Term goTerm;
		boolean visited=false;
		long unaffected_ref = 0L;
		long unaffected_alt = 0L;
		long affected_ref = 0L;
		long affected_alt = 0L;
		private Double _fisher=null;
		
		final Set<Node> parents = new HashSet<>();
		Node(final GoTree.Term goTerm)
			{
			this.goTerm = goTerm;
			}
		@Override
		public int hashCode() {
			return goTerm.hashCode();
			}
		@Override
		public boolean equals(Object obj) {
			if(obj==this) return true;
			return goTerm.equals(Node.class.cast(obj).goTerm);
			}
		void resetVisitedFlag() 
			{
			this.visited=false;
			this.parents.stream().forEach(N->N.resetVisitedFlag());
			}
		void visit(
			final long unaffected_ref,
			final long unaffected_alt,	
			final long affected_ref,
			final long affected_alt
			)
			{
			if(this.visited) return;
			this.visited=true;
			this.unaffected_ref += unaffected_ref;
			this.unaffected_alt += unaffected_alt;
			this.affected_ref += affected_ref;
			this.affected_alt += affected_alt;
			
			this.parents.stream().forEach(N->N.visit(
					unaffected_ref,
					unaffected_alt,
					affected_ref,
					affected_alt
				));
			}
		double fisher() {
			if(this._fisher==null)
				{
				this._fisher = FisherExactTest.compute(
					(int)this.unaffected_ref,
					(int)this.unaffected_alt,
					(int)this.affected_ref,
					(int)this.affected_alt
					).getAsDouble();
				}
			return this._fisher.doubleValue();
			}
		}
	
	
	public VcfBurdenGoEnrichment()
		{
		}
	
	 	
	@Override
	public int doWork(final List<String> args) {
		if(StringUtil.isBlank(this.goUri)) {
			LOG.error("Undefined GOs uri.");
			return -1;
			}
		
		if(this.geneFile==null || !this.geneFile.exists()) {
			LOG.error("Undefined gene file option.");
			return -1;
			}
			
		try {
			final GoTree gotree = new GoTree.Parser().
					setIgnoreDbXRef(true).
					//setIgnoreSynonyms(true). WE need synonyms with GOA.
					parse(this.goUri);
			
			List<GoTree.Term> terms = new ArrayList<>(gotree.getTerms());
			final Map<GoTree.Term,Node> term2node = new HashMap<>();
			// build the node TREE
			while(!terms.isEmpty())
				{
				int i=0;
				while(i<terms.size())
					{
					final GoTree.Term t= terms.get(i);
					
					
					if(!t.hasRelations())
						{
						term2node.put(t,new Node(t));
						terms.remove(i);
						}
					else if(t.getRelations().stream().
							allMatch(L->term2node.containsKey(L.getTo())))
						{

						
						final Node n = new Node(t);
						n.parents.addAll(
							t.getRelations().stream().
							map(L->term2node.get(L.getTo())).
							collect(Collectors.toSet())
							);
						term2node.put(t, n);
						terms.remove(i);
						}
					else
						{
						i++;
						}
					}
				}
			terms=null;
			
			final Map<String,Set<Node>> gene2node = new HashMap<>();
			BufferedReader r= IOUtils.openFileForBufferedReading(this.geneFile);
			String line;
			while((line=r.readLine())!=null) {
				if(line.isEmpty() || line.startsWith("#")) continue;
				int t=line.indexOf('\t');
				if(t==-1) {
					r.close();
					LOG.error("tab missing in "+line+" of "+this.geneFile);
					return -1;
					}
				final String gene=line.substring(0,t).trim();
				if(StringUtil.isBlank(gene)){
					r.close();
					LOG.error("Emtpy gene in "+line);
					return -1;
					}

				//using getTermByName because found sysnonym in GOA
				final GoTree.Term term= gotree.getTermByName(line.substring(t+1).trim());
				if(term==null)
					{
					LOG.warning("Don't know this GO term in "+line+" of "+this.geneFile+". Could be obsolete or synonym. Skipping.");
					continue;
					}
				final Node node = term2node.get(term);
				if(node==null)
					{
					r.close();
					LOG.error("Don't know this node in "+line+" of "+this.geneFile);
					return -1;
					}
				Set<Node> nodes = gene2node.get(gene);
				if(nodes==null) {
					nodes= new HashSet<>();
					gene2node.put(gene, nodes);
					}
				nodes.add(node);
				};
			r.close();
			
			VcfIterator iter = openVcfIterator(oneFileOrNull(args));
			final VCFHeader header = iter.getHeader();
			final VepPredictionParser vepParser = new VepPredictionParserFactory(header).get();
			final AnnPredictionParser annParser = new AnnPredictionParserFactory(header).get();

			final Set<Pedigree.Person> persons;
			if(this.pedFile!=null)
				{
				final Pedigree pedigree = Pedigree.newParser().parse(pedFile);
				persons = new Pedigree.CaseControlExtractor().extract(header, pedigree);
				}
			else
				{
				persons = new Pedigree.CaseControlExtractor().extract(header);
				}
			
			if(!persons.stream().anyMatch(P->P.isAffected())) {
				LOG.error("No Affected individual");
				return -1;
			}
			if(!persons.stream().anyMatch(P->P.isUnaffected())) {
				LOG.error("No unaffected individual");
				return -1;
			}
			
			final List<String> lookColumns=Arrays.asList(
					"CCDS",
					"Feature",
					"ENSP",
					"Gene",
					"HGNC",
					"HGNC_ID",					
					"SYMBOL",					
					"RefSeq"					
					);
			final SAMSequenceDictionaryProgress progress = new SAMSequenceDictionaryProgress(header).logger(LOG);
			while(iter.hasNext())
				{
				final VariantContext ctx = progress.watch(iter.next());
				final Set<String> genes = new HashSet<>();
				
				for(final String predStr: ctx.getAttributeAsList(vepParser.getTag()).stream().map(O->String.class.cast(O)).collect(Collectors.toList()))
					{
					final VepPredictionParser.VepPrediction pred = vepParser.parseOnePrediction(ctx,predStr);
					for(final String col:lookColumns) {
						final String token = pred.getByCol(col);
						if(!StringUtil.isBlank(token))
							{
							genes.add(token);
							}
						}
					}
				
				for(final String predStr: ctx.getAttributeAsList(annParser.getTag()).stream().map(O->String.class.cast(O)).collect(Collectors.toList())) {
					final AnnPredictionParser.AnnPrediction pred = annParser.parseOnePrediction(predStr);
					final String token = pred.getGeneName();
					if(!StringUtil.isBlank(token))
						{
						genes.add(token);
						}
					}
				if(genes.isEmpty()) continue;

				final Set<Node> nodes = genes.stream().
						filter(G->gene2node.containsKey(G)).
						flatMap(G->gene2node.get(G).stream()).
						collect(Collectors.toSet());
				if(nodes.isEmpty()) continue;

				nodes.stream().forEach(N->N.resetVisitedFlag());
				final long unaffected_ref = 
						persons.stream().
						filter(P->P.isUnaffected()).
						map(P->ctx.getGenotype(P.getId())).
						filter(G->G!=null && G.isCalled() && G.getAlleles().stream().allMatch(A->A.isReference())).
						count();
						
				final long unaffected_alt  = 
						persons.stream().
						filter(P->P.isUnaffected()).
						map(P->ctx.getGenotype(P.getId())).
						filter(G->G!=null && G.isCalled() && G.getAlleles().stream().anyMatch(A->A.isNonReference())).
						count()
						;
				final long affected_ref = 
						persons.stream().
						filter(P->P.isAffected()).
						map(P->ctx.getGenotype(P.getId())).
						filter(G->G!=null && G.isCalled() && G.getAlleles().stream().allMatch(A->A.isReference())).
						count();
				final long affected_alt   = 
						persons.stream().
						filter(P->P.isAffected()).
						map(P->ctx.getGenotype(P.getId())).
						filter(G->G!=null && G.isCalled() && G.getAlleles().stream().anyMatch(A->A.isNonReference())).
						count()
						;
				nodes.stream().forEach(N->N.visit(unaffected_ref, unaffected_alt, affected_ref, affected_alt));
				}			
			iter.close();
			progress.finish();
			
			final PrintWriter pw = super.openFileOrStdoutAsPrintWriter(this.outputFile);
			
			term2node.values().stream().
				filter(N->N.unaffected_ref+N.unaffected_alt+N.affected_ref+N.affected_alt>0L).
				sorted((n1,n2)->Double.compare(n2.fisher(), n1.fisher())).
				forEach(N->{
					pw.print(N.goTerm.getAcn());
					pw.print('\t');
					pw.print(N.fisher());
					pw.print('\t');
					pw.print(N.goTerm.getName());
					pw.print('\t');
					pw.print(N.unaffected_ref);
					pw.print('\t');
					pw.print(N.unaffected_alt);
					pw.print('\t');
					pw.print(N.affected_ref);
					pw.print('\t');
					pw.print(N.affected_alt);
					pw.println();
					}
					);
			pw.flush();
			pw.close();
			return 0;
		} catch (final Exception err) {
			LOG.error(err);
			return -1;
			}
		}
	
	public static void main(String[] args)
		{
		new VcfBurdenGoEnrichment().instanceMainWithExit(args);
		}
	}
