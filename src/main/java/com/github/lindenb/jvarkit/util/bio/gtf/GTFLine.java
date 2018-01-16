/*
The MIT License (MIT)

Copyright (c) 2017 Pierre Lindenbaum

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
package com.github.lindenb.jvarkit.util.bio.gtf;

import htsjdk.samtools.util.Locatable;
import htsjdk.tribble.Feature;

import java.util.Iterator;
import java.util.Map;

public interface GTFLine 
	extends Locatable,Iterable<Map.Entry<String, String>>,Feature

	{
	public static final int NO_PHASE=-1;
	public static final char NO_STRAND='.';
	
	/** get the original line of the GTF/GFF line */
	public String getLine();
	public String getSource();
	public String getType();
	public Double getScore();
	public char getStrand();
	public Iterator<Map.Entry<String, String>> iterator();
	public Map<String, String> getAttributes();
	public String getAttribute(final String key);
	public int getPhase();
	}