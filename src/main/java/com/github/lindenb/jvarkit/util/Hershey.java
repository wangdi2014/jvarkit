package com.github.lindenb.jvarkit.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;


public class Hershey
	{
	private static final Logger LOG=Logger.getLogger("jvarkit");
	private double scalex=15;
	private double scaley=15;
	private enum Op {MOVETO,LINETO};
	private static class PathOp
		{
		Op operator;
		double x;
		double y;
		@Override
		public String toString()
			{
			return "("+operator+","+x+","+y+")";
			}
		}
	
	private static final String LETTERS[]=new String[]{
		"  9MWRMNV RRMVV RPSTS",
		" 16MWOMOV ROMSMUNUPSQ ROQSQURUUSVOV",
		" 11MXVNTMRMPNOPOSPURVTVVU",
		" 12MWOMOV ROMRMTNUPUSTURVOV",
		" 12MWOMOV ROMUM ROQSQ ROVUV",
		"  9MVOMOV ROMUM ROQSQ",
		" 15MXVNTMRMPNOPOSPURVTVVUVR RSRVR",
		"  9MWOMOV RUMUV ROQUQ",
		"  3PTRMRV",
		"  7NUSMSTRVPVOTOS",
		"  9MWOMOV RUMOS RQQUV",
		"  6MVOMOV ROVUV",
		" 12LXNMNV RNMRV RVMRV RVMVV",
		"  9MWOMOV ROMUV RUMUV",
		" 14MXRMPNOPOSPURVSVUUVSVPUNSMRM",
		" 10MWOMOV ROMSMUNUQSROR",
		" 17MXRMPNOPOSPURVSVUUVSVPUNSMRM RSTVW",
		" 13MWOMOV ROMSMUNUQSROR RRRUV",
		" 13MWUNSMQMONOOPPTRUSUUSVQVOU",
		"  6MWRMRV RNMVM",
		"  9MXOMOSPURVSVUUVSVM",
		"  6MWNMRV RVMRV",
		" 12LXNMPV RRMPV RRMTV RVMTV",
		"  6MWOMUV RUMOV",
		"  7MWNMRQRV RVMRQ",
		"  9MWUMOV ROMUM ROVUV"

		};
	
	private static final String DIGITS[]=new String[]{
		  " 12MWRMPNOPOSPURVTUUSUPTNRM",
		  "  4MWPORMRV",
		  "  9MWONQMSMUNUPTROVUV",
		  " 15MWONQMSMUNUPSQ RRQSQURUUSVQVOU",
		  "  7MWSMSV RSMNSVS",
		  " 14MWPMOQQPRPTQUSTURVQVOU RPMTM",
		  " 14MWTMRMPNOPOSPURVTUUSTQRPPQOS",
		  "  6MWUMQV ROMUM",
		  " 19MWQMONOPQQSQUPUNSMQM RQQOROUQVSVUUURSQ",
		  " 14MWUPTRRSPROPPNRMTNUPUSTURVPV"
		};

	private Set<Character> undefined=new HashSet<Character>();
	private  String charToHersheyString(char c)
		{
		if(Character.isLetter(c))
			{
			return LETTERS[Character.toUpperCase(c)-'A'];
			}
		if(Character.isDigit(c))
			{
			return DIGITS[Character.toUpperCase(c)-'0'];
			}
		switch(c)
			{
			case '.': return "  6PURURVSVSURU";//210
			case ',': return "  7PUSVRVRUSUSWRY";//211
			case ':': return " 12PURPRQSQSPRP RRURVSVSURU";//212
			case ';': return " 13PURPRQSQSPRP RSVRVRUSUSWRY";//213
			case '!': return " 12PURMRR RSMSR RRURVSVSURU";//214
			case '?': return " 17NWPNRMSMUNUPRQRRSRSQUP RRURVSVSURU";//215
			case '\'':return "  3PTRMRQ";//216
			case '\"':return "  6NVPMPQ RTMTQ";//217
			case '/': return "  3MWVLNW";//220
			case '(': return "  7OVTLRNQPQSRUTW";//221
			case ')': return "  7NUPLRNSPSSRUPW";//222
			case '|': return "  3PTRLRW";//223
			case '#': return " 12MXRLPW RULSW ROPVP ROSVS";//233
			default:
				{
				if(!undefined.contains(c))
					{
					LOG.info("missing hershey char: "+c);
					undefined.add(c);
					}
				return null;
				}
			}
		}
	
	private Map<Character, List<PathOp>> letter2path=new HashMap<Character, List<PathOp>>();
	
	private List<PathOp> charToPathOp(char letter)
		{
		int i;
		if(letter==' ') return Collections.emptyList();
		List<PathOp> array=letter2path.get(letter);
		if(array!=null) return array;
		
		String s=this.charToHersheyString(letter);
		if(s==null) return Collections.emptyList();
		
		int num_vertices=0;
		for( i=0;i< 3;++i)
			{
			char c=s.charAt(i);
			if(Character.isSpaceChar(c)) continue;
			num_vertices = num_vertices*10+(c-'0');
			}
		num_vertices--;
		i+=2;
		int nop=0;
		array=new ArrayList<Hershey.PathOp>(num_vertices);
		while(nop<num_vertices)
			{
			PathOp pathOp=new PathOp();
			pathOp.operator=(array.isEmpty()?Op.MOVETO:Op.LINETO);
			char c=s.charAt(i++);
			if(c==' ')
				{
				c=s.charAt(i++);
				if(c!='R') throw new IllegalArgumentException(s);
				nop++;
				pathOp.operator=Op.MOVETO;
				c=s.charAt(i++);
				}
			pathOp.x=c-'R';
			c=s.charAt(i++);
			pathOp.y=c-'R';
			nop++;
			array.add(pathOp);
			}
		letter2path.put(letter, array);
		return array;
		}
	public void paint(
			Graphics2D g,
			String s,
			Shape shape
			)
		{
		Rectangle2D r=shape.getBounds2D();
		paint(g,s,r.getX(),r.getY(),r.getWidth(),r.getHeight());
		}
	public void paint(
			Graphics2D g,
			String s,
			double x, double y,
			double width, double height
			)
		{
		if(s.isEmpty() || width==0 || height==0) return;
		
		double dx=width/s.length();
		for(int i=0;i < s.length();++i)
			{
			List<PathOp> array=charToPathOp(s.charAt(i));
			
			for(int n=0;n< array.size();++n)
				{
				PathOp p2=array.get(n);
				if(p2.operator==Op.MOVETO) continue;
				PathOp p1=array.get(n-1);
				
				double x1=(p1.x/this.scalex)*dx + x+dx*i +dx/2.0;
				double y1=(p1.y/this.scaley)*height +y +height/2.0 ;
				
				double x2=(p2.x/this.scalex)*dx + x+dx*i +dx/2.0;
				double y2=(p2.y/this.scaley)*height +y +height/2.0 ;
				
				g.draw(new Line2D.Double(x1, y1, x2, y2));
				}
			}
		}
	public String svgPath(
			String s,
			Shape shape
			)
		{
		Rectangle2D r=shape.getBounds2D();
		return svgPath(s,r.getX(),r.getY(),r.getWidth(),r.getHeight());
		}

	
	public String svgPath(
			String s,
			double x, double y,
			double width, double height
			)
		{
		StringWriter sw=new StringWriter();
		
		
		if(s.isEmpty() || width==0 || height==0) return "";
		
		double dx=width/s.length();
		for(int i=0;i < s.length();++i)
			{
			List<PathOp> array=charToPathOp(s.charAt(i));
			
			for(int n=0;n< array.size();++n)
				{
				PathOp p2=array.get(n);
				double x2=(p2.x/this.scalex)*dx + x+dx*i +dx/2.0;
				double y2=(p2.y/this.scaley)*height +y +height/2.0 ;
				switch(p2.operator)
					{
					case LINETO: sw.append("L ");break;
					case MOVETO: sw.append("M ");break;
					default: throw new IllegalStateException();
					}
				sw.append(String.format("%.3f",x2));
				sw.append(" ");
				sw.append(String.format("%.3f",y2));
				sw.append(" ");
				}
			
			}
		sw.append("z");
		return sw.toString();
		}
	
	public static void main(String[] args)
		{
		BufferedImage img=new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g=img.createGraphics();
		g.setColor(Color.RED);
		new Hershey().paint(g,"01234567891",0,0,300,100);
		g.dispose();
		JOptionPane.showMessageDialog(null,new JLabel(new ImageIcon(img)));
		
		}
	
	
	}
