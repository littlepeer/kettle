/**********************************************************************
 **                                                                   **
 **               This code belongs to the KETTLE project.            **
 **                                                                   **
 ** Kettle, from version 2.2 on, is released into the public domain   **
 ** under the Lesser GNU Public License (LGPL).                       **
 **                                                                   **
 ** For more details, please read the document LICENSE.txt, included  **
 ** in this project                                                   **
 **                                                                   **
 ** http://www.kettle.be                                              **
 ** info@kettle.be                                                    **
 **                                                                   **
 **********************************************************************/

package org.pentaho.di.trans.steps.textfileinput;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import org.apache.commons.vfs.FileObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.fileinput.FileInputList;
import org.pentaho.di.core.logging.LogWriter;
import org.pentaho.di.core.playlist.FilePlayListAll;
import org.pentaho.di.core.playlist.FilePlayListReplay;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.step.errorhandling.AbstractFileErrorHandler;
import org.pentaho.di.trans.step.errorhandling.CompositeFileErrorHandler;
import org.pentaho.di.trans.step.errorhandling.FileErrorHandler;
import org.pentaho.di.trans.step.errorhandling.FileErrorHandlerContentLineNumber;
import org.pentaho.di.trans.step.errorhandling.FileErrorHandlerMissingFiles;


/**
 * Read all sorts of text files, convert them to rows and writes these to one or
 * more output streams.
 * 
 * @author Matt
 * @since 4-apr-2003
 */
public class TextFileInput extends BaseStep implements StepInterface
{
	private static final int BUFFER_SIZE_INPUT_STREAM = 500;
    
    private static LogWriter log = LogWriter.getInstance();
    
    private TextFileInputMeta meta;

	private TextFileInputData data;

	private long lineNumberInFile;
    
    private TransMeta transmeta;

	public TextFileInput(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans)
	{
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
        this.transmeta = transMeta;
	}

	public static final String getLine(LogWriter log, InputStreamReader reader, int formatNr, StringBuffer line) throws KettleFileException
	{
		int c = 0;
		line.setLength(0);
        
		try
		{
            switch(formatNr)
            {
            case TextFileInputMeta.FILE_FORMAT_DOS:
                {
                    while (c >= 0)
                    {
                        c = reader.read();
                        
                        if (c == '\r' || c == '\n' )
                        {
                            c = reader.read(); // skip \n and \r
                            if( c != '\r' && c != '\n' ) 
                            { 
                                // make sure its really a linefeed or cariage return
                                // raise an error this is not a DOS file
                                // so we have pulled a character from the next line
                                throw new KettleFileException("DOS format was specified but only a single line feed character was found, not 2");                                 
                            }
                            return line.toString();
                        }
                        if (c >= 0) line.append((char) c);
                    }
                }
                break;
            case TextFileInputMeta.FILE_FORMAT_UNIX:
                {
                    while (c >= 0)
                    {
                        c = reader.read();
                        
    					if (c == '\n' || c == '\r')
    					{
    						return line.toString();
    					}
    					if (c >= 0) line.append((char) c);
                    }
                }
                break;
            case TextFileInputMeta.FILE_FORMAT_MIXED:
        		 // in mixed mode we suppose the LF is the last char and CR is ignored
        			// not for MAC OS 9 but works for Mac OS X. Mac OS 9 can use UNIX-Format
        		{
                    while (c >= 0)
                    {
                        c = reader.read();
                        
        				if (c == '\n')
        				{
        					return line.toString();
        				}
        				else if (c != '\r')
        				{
        					if (c >= 0) line.append((char) c);
        				}
        			}
        		}
                break;
            }
		}
		catch(KettleFileException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			if (line.length() == 0)
			{
				throw new KettleFileException("Exception reading line: " + e.toString(), e);
			}
			return line.toString();
		}
		if (line.length() > 0) return line.toString();

		return null;
	}
	
	public static final String[] guessStringsFromLine(String line, TextFileInputMeta inf) throws KettleException
	{
		List<String> strings = new ArrayList<String>();
        int fieldnr;
        
		String pol; // piece of line

		try
		{
			if (line == null) return null;

			if (inf.getFileType().equalsIgnoreCase("CSV"))
			{
				// Split string in pieces, only for CSV!

				fieldnr = 0;
				int pos = 0;
				int length = line.length();
				boolean dencl = false;

                int len_encl = (inf.getEnclosure() == null ? 0 : inf.getEnclosure().length());
                int len_esc = (inf.getEscapeCharacter() == null ? 0 : inf.getEscapeCharacter().length());

				while (pos < length)
				{
					int from = pos;
					int next;

					boolean encl_found;
					boolean contains_escaped_enclosures = false;
					boolean contains_escaped_separators = false;

					// Is the field beginning with an enclosure?
					// "aa;aa";123;"aaa-aaa";000;...
					if (len_encl > 0 && line.substring(from, from + len_encl).equalsIgnoreCase(inf.getEnclosure()))
					{
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "encl substring=[" + line.substring(from, from + len_encl) + "]");
						encl_found = true;
						int p = from + len_encl;

						boolean is_enclosure = len_encl > 0 && p + len_encl < length
								&& line.substring(p, p + len_encl).equalsIgnoreCase(inf.getEnclosure());
						boolean is_escape = len_esc > 0 && p + len_esc < length
								&& line.substring(p, p + len_esc).equalsIgnoreCase(inf.getEscapeCharacter());

						boolean enclosure_after = false;
						
						// Is it really an enclosure? See if it's not repeated twice or escaped!
						if ((is_enclosure || is_escape) && p < length - 1) 
						{
							String strnext = line.substring(p + len_encl, p + 2 * len_encl);
							if (strnext.equalsIgnoreCase(inf.getEnclosure()))
							{
								p++;
								enclosure_after = true;
								dencl = true;

								// Remember to replace them later on!
								if (is_escape) contains_escaped_enclosures = true; 
							}
						}

						// Look for a closing enclosure!
						while ((!is_enclosure || enclosure_after) && p < line.length())
						{
							p++;
							enclosure_after = false;
							is_enclosure = len_encl > 0 && p + len_encl < length && line.substring(p, p + len_encl).equals(inf.getEnclosure());
							is_escape = len_esc > 0 && p + len_esc < length && line.substring(p, p + len_esc).equals(inf.getEscapeCharacter());

							// Is it really an enclosure? See if it's not repeated twice or escaped!
							if ((is_enclosure || is_escape) && p < length - 1) // Is
							{
								String strnext = line.substring(p + len_encl, p + 2 * len_encl);
								if (strnext.equals(inf.getEnclosure()))
								{
									p++;
									enclosure_after = true;
									dencl = true;

									// Remember to replace them later on!
									if (is_escape) contains_escaped_enclosures = true; // remember
								}
							}
						}

						if (p >= length) next = p;
						else next = p + len_encl;

                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "End of enclosure @ position " + p);
					}
					else
					{
						encl_found = false;
						boolean found = false;
						int startpoint = from;
						int tries = 1;
						do
						{
							next = line.indexOf(inf.getSeparator(), startpoint);

							// See if this position is preceded by an escape character.
							if (len_esc > 0 && next - len_esc > 0)
							{
								String before = line.substring(next - len_esc, next);

								if (inf.getEscapeCharacter().equals(before))
								{
									// take the next separator, this one is escaped...
									startpoint = next + 1; 
									tries++;
									contains_escaped_separators = true;
								}
								else
								{
									found = true;
								}
							}
							else
							{
								found = true;
							}
						}
						while (!found && next >= 0);
					}
					if (next == -1) next = length;

					if (encl_found)
					{
						pol = line.substring(from + len_encl, next - len_encl);
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "Enclosed field found: [" + pol + "]");
					}
					else
					{
						pol = line.substring(from, next);
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "Normal field found: [" + pol + "]");
					}

					if (dencl)
					{
						StringBuffer sbpol = new StringBuffer(pol);
						int idx = sbpol.indexOf(inf.getEnclosure() + inf.getEnclosure());
						while (idx >= 0)
						{
							sbpol.delete(idx, idx + inf.getEnclosure().length());
							idx = sbpol.indexOf(inf.getEnclosure() + inf.getEnclosure());
						}
						pol = sbpol.toString();
					}

					//	replace the escaped enclosures with enclosures... 
					if (contains_escaped_enclosures) 
					{
						String replace = inf.getEscapeCharacter() + inf.getEnclosure();
						String replaceWith = inf.getEnclosure();

						pol = Const.replace(pol, replace, replaceWith);
					}

					//replace the escaped separators with separators... 
					if (contains_escaped_separators) 
					{
						String replace = inf.getEscapeCharacter() + inf.getSeparator();
						String replaceWith = inf.getSeparator();

						pol = Const.replace(pol, replace, replaceWith);
					}

					// Now add pol to the strings found!
					strings.add(pol);

					pos = next + 1;
					fieldnr++;
				}
				if ( pos == length )
				{
					if (log.isRowLevel()) log.logRowlevel("convert line to row", "End of line empty field found: []");
					strings.add("");
                    fieldnr++;
				}
			}
			else
			{
				// Fixed file format: Simply get the strings at the required positions...
				for (int i = 0; i < inf.getInputFields().length; i++)
				{
					TextFileInputField field = inf.getInputFields()[i];

					int length = line.length();

					if (field.getPosition() + field.getLength() <= length)
					{
						strings.add( line.substring(field.getPosition(), field.getPosition() + field.getLength()) );
					}
					else
					{
						if (field.getPosition() < length)
						{
							strings.add( line.substring(field.getPosition()) );
						}
						else
						{
							strings.add( "" );
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new KettleException("Error converting line : " + e.toString(), e);
		}

		return strings.toArray(new String[strings.size()]);
	}
    

	public static final String[] convertLineToStrings(String line, TextFileInputMeta inf) throws KettleException
	{
		String[] strings = new String[inf.getInputFields().length];
        int fieldnr;
        
		String pol; // piece of line

		try
		{
			if (line == null) return null;

			if (inf.getFileType().equalsIgnoreCase("CSV"))
			{
				// Split string in pieces, only for CSV!

				fieldnr = 0;
				int pos = 0;
				int length = line.length();
				boolean dencl = false;

                int len_encl = (inf.getEnclosure() == null ? 0 : inf.getEnclosure().length());
                int len_esc = (inf.getEscapeCharacter() == null ? 0 : inf.getEscapeCharacter().length());

				while (pos < length)
				{
					int from = pos;
					int next;

					boolean encl_found;
					boolean contains_escaped_enclosures = false;
					boolean contains_escaped_separators = false;

					// Is the field beginning with an enclosure?
					// "aa;aa";123;"aaa-aaa";000;...
					if (len_encl > 0 && line.substring(from, from + len_encl).equalsIgnoreCase(inf.getEnclosure()))
					{
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "encl substring=[" + line.substring(from, from + len_encl) + "]");
						encl_found = true;
						int p = from + len_encl;

						boolean is_enclosure = len_encl > 0 && p + len_encl < length
								&& line.substring(p, p + len_encl).equalsIgnoreCase(inf.getEnclosure());
						boolean is_escape = len_esc > 0 && p + len_esc < length
								&& line.substring(p, p + len_esc).equalsIgnoreCase(inf.getEscapeCharacter());

						boolean enclosure_after = false;
						
						// Is it really an enclosure? See if it's not repeated twice or escaped!
						if ((is_enclosure || is_escape) && p < length - 1) 
						{
							String strnext = line.substring(p + len_encl, p + 2 * len_encl);
							if (strnext.equalsIgnoreCase(inf.getEnclosure()))
							{
								p++;
								enclosure_after = true;
								dencl = true;

								// Remember to replace them later on!
								if (is_escape) contains_escaped_enclosures = true; 
							}
						}

						// Look for a closing enclosure!
						while ((!is_enclosure || enclosure_after) && p < line.length())
						{
							p++;
							enclosure_after = false;
							is_enclosure = len_encl > 0 && p + len_encl < length && line.substring(p, p + len_encl).equals(inf.getEnclosure());
							is_escape = len_esc > 0 && p + len_esc < length && line.substring(p, p + len_esc).equals(inf.getEscapeCharacter());

							// Is it really an enclosure? See if it's not repeated twice or escaped!
							if ((is_enclosure || is_escape) && p < length - 1) // Is
							{
								String strnext = line.substring(p + len_encl, p + 2 * len_encl);
								if (strnext.equals(inf.getEnclosure()))
								{
									p++;
									enclosure_after = true;
									dencl = true;

									// Remember to replace them later on!
									if (is_escape) contains_escaped_enclosures = true; // remember
								}
							}
						}

						if (p >= length) next = p;
						else next = p + len_encl;

                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "End of enclosure @ position " + p);
					}
					else
					{
						encl_found = false;
						boolean found = false;
						int startpoint = from;
						int tries = 1;
						do
						{
							next = line.indexOf(inf.getSeparator(), startpoint);

							// See if this position is preceded by an escape character.
							if (len_esc > 0 && next - len_esc > 0)
							{
								String before = line.substring(next - len_esc, next);

								if (inf.getEscapeCharacter().equals(before))
								{
									// take the next separator, this one is escaped...
									startpoint = next + 1; 
									tries++;
									contains_escaped_separators = true;
								}
								else
								{
									found = true;
								}
							}
							else
							{
								found = true;
							}
						}
						while (!found && next >= 0);
					}
					if (next == -1) next = length;

					if (encl_found)
					{
						pol = line.substring(from + len_encl, next - len_encl);
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "Enclosed field found: [" + pol + "]");
					}
					else
					{
						pol = line.substring(from, next);
                        if (log.isRowLevel()) log.logRowlevel("convert line to row", "Normal field found: [" + pol + "]");
					}

					if (dencl)
					{
						StringBuffer sbpol = new StringBuffer(pol);
						int idx = sbpol.indexOf(inf.getEnclosure() + inf.getEnclosure());
						while (idx >= 0)
						{
							sbpol.delete(idx, idx + inf.getEnclosure().length());
							idx = sbpol.indexOf(inf.getEnclosure() + inf.getEnclosure());
						}
						pol = sbpol.toString();
					}

					//	replace the escaped enclosures with enclosures... 
					if (contains_escaped_enclosures) 
					{
						String replace = inf.getEscapeCharacter() + inf.getEnclosure();
						String replaceWith = inf.getEnclosure();

						pol = Const.replace(pol, replace, replaceWith);
					}

					//replace the escaped separators with separators... 
					if (contains_escaped_separators) 
					{
						String replace = inf.getEscapeCharacter() + inf.getSeparator();
						String replaceWith = inf.getSeparator();

						pol = Const.replace(pol, replace, replaceWith);
					}

					// Now add pol to the strings found!
					strings[fieldnr]=pol;

					pos = next + 1;
					fieldnr++;
				}
				if ( pos == length )
				{
					if (log.isRowLevel()) log.logRowlevel("convert line to row", "End of line empty field found: []");
					strings[fieldnr]= "";
                    fieldnr++;
				}
			}
			else
			{
				// Fixed file format: Simply get the strings at the required positions...
				for (int i = 0; i < inf.getInputFields().length; i++)
				{
					TextFileInputField field = inf.getInputFields()[i];

					int length = line.length();

					if (field.getPosition() + field.getLength() <= length)
					{
						strings[i] = line.substring(field.getPosition(), field.getPosition() + field.getLength());
					}
					else
					{
						if (field.getPosition() < length)
						{
							strings[i] = line.substring(field.getPosition());
						}
						else
						{
							strings[i] = "";
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new KettleException("Error converting line : " + e.toString(), e);
		}

		return strings;
	}
    
    public static final Object[] convertLineToRow(TextFileLine textFileLine, TextFileInputMeta info, RowMetaInterface outputRowMeta, RowMetaInterface convertRowMeta, String fname, long rowNr, FileErrorHandler errorHandler) throws KettleException
    {
        if (textFileLine == null || textFileLine.line == null || textFileLine.line.length() == 0) return null;
        Object[] r = new Object[outputRowMeta.size()];
        
        int nrfields = info.getInputFields().length;
        int fieldnr;
        
        Long errorCount = null;
        if (info.isErrorIgnored() && info.getErrorCountField() != null && info.getErrorCountField().length() > 0)
        {
            errorCount = new Long(0L);
        }
        String errorFields = null;
        if (info.isErrorIgnored() && info.getErrorFieldsField() != null && info.getErrorFieldsField().length() > 0)
        {
            errorFields = "";
        }
        String errorText = null;
        if (info.isErrorIgnored() && info.getErrorTextField() != null && info.getErrorTextField().length() > 0)
        {
            errorText = "";
        }
        
        try
        {
            // System.out.println("Convertings line to string ["+line+"]");
            String[] strings = convertLineToStrings(textFileLine.line, info);

            for (fieldnr = 0; fieldnr < nrfields; fieldnr++)
            {
                TextFileInputField f = info.getInputFields()[fieldnr];
                ValueMetaInterface valueMeta = outputRowMeta.getValueMeta(fieldnr);
                ValueMetaInterface convertMeta = convertRowMeta.getValueMeta(fieldnr);
                
                Object value;

                String nullif = fieldnr < nrfields ? f.getNullString() : "";
                String ifnull = fieldnr < nrfields ? f.getIfNullValue() : "";
                int trim_type = fieldnr < nrfields ? f.getTrimType() : TextFileInputMeta.TYPE_TRIM_NONE;

                if (fieldnr < strings.length)
                {
                    String pol = strings[fieldnr];
                    try
                    {
                        value = convertValue(pol, valueMeta, convertMeta, nullif, ifnull, trim_type);
                    }
                    catch (Exception e)
                    {
                        // OK, give some feedback!
                        String message = "Couldn't parse field [" + valueMeta.toStringMeta() + "] with value [" + pol + "], format ["+valueMeta.getConversionMask()+"]";
                        
                        if (info.isErrorIgnored())
                        {
                            LogWriter.getInstance().logBasic(fname, "WARNING: " + message+" : " + e.getMessage());

                            value = null;

                            if (errorCount != null)
                            {
                                errorCount=new Long( errorCount.longValue()+1L );
                            }
                            if (errorFields != null)
                            {
                                StringBuffer sb = new StringBuffer(errorFields);
                                if (sb.length() > 0) sb.append("\t"); // TODO document this change
                                sb.append(valueMeta.getName());
                                errorFields = sb.toString();
                            }
                            if (errorText != null)
                            {
                                StringBuffer sb = new StringBuffer(errorText);
                                if (sb.length() > 0) sb.append(Const.CR);
                                sb.append(message);
                                errorText=sb.toString();
                            }
                            if (errorHandler != null)
                            {
                                errorHandler.handleLineError(textFileLine.lineNumber, AbstractFileErrorHandler.NO_PARTS);
                            }

                            if (info.isErrorLineSkipped()) r=null; // compensates for stmt: r.setIgnore();
                        }
                        else
                        {
                            throw new KettleException(message, e);
                        }
                    }
                }
                else
                {
                    // No data found: TRAILING NULLCOLS: add null value...
                    value = null;
                }

                // Now add value to the row!
                r[fieldnr] = value;
            }

            // Support for trailing nullcols!
            // Should be OK at allocation time, but it doesn't hurt :-)
            if (fieldnr < nrfields)
            {
                for (int i = fieldnr; i < info.getInputFields().length; i++)
                {
                    r[i] = null;
                }
            }

            // Add the error handling fields...
            int index = nrfields;
            if (errorCount != null) 
            {
                r[index]=errorCount;
                index++;
            }
            if (errorFields != null)
            {
                r[index]=errorFields;
                index++;
            }
            if (errorText != null)
            {
                r[index]=errorText;
                index++;
            }
            
            // Possibly add a filename...
            if (info.includeFilename())
            {
                r[index]=fname;
                index++;
            }

            // Possibly add a row number...
            if (info.includeRowNumber())
            {
                r[index] = new Long(rowNr);
                index++;
            }
        }
        catch (Exception e)
        {
            throw new KettleException("Error converting line", e);
        }

        return r;

    }
    
    public static final Object convertValue( String pol, ValueMetaInterface valueMeta, ValueMetaInterface convertMeta, String nullif, String ifNull, int trim_type) throws KettleValueException
    {
        // null handling and conversion of value to null
        //
        if (Const.isEmpty(pol) || pol.equalsIgnoreCase(ifNull))
        {
            if (ifNull!=null && ifNull.length()!=0)
            {
                pol = ifNull;
            }
        }
        
        if (pol == null || pol.length() == 0 || pol.equalsIgnoreCase(nullif)) 
        {
            return null;
        }
        
        // Trimming
        switch (trim_type)
        {
        case TextFileInputMeta.TYPE_TRIM_LEFT:
            {
                StringBuffer strpol = new StringBuffer(pol);
                while (strpol.length() > 0 && strpol.charAt(0) == ' ')
                    strpol.deleteCharAt(0);
                pol=strpol.toString();
            }
            break;
        case TextFileInputMeta.TYPE_TRIM_RIGHT:
            {
                StringBuffer strpol = new StringBuffer(pol);
                while (strpol.length() > 0 && strpol.charAt(strpol.length() - 1) == ' ')
                    strpol.deleteCharAt(strpol.length() - 1);
                pol=strpol.toString();
            }
            break;
        case TextFileInputMeta.TYPE_TRIM_BOTH:
            StringBuffer strpol = new StringBuffer(pol);
            {
                while (strpol.length() > 0 && strpol.charAt(0) == ' ')
                    strpol.deleteCharAt(0);
                while (strpol.length() > 0 && strpol.charAt(strpol.length() - 1) == ' ')
                    strpol.deleteCharAt(strpol.length() - 1);
                pol=strpol.toString();
            }
            break;
        default:
            break;
        }
        
        // On with the regular program...
        // Simply call the ValueMeta routines to do the conversion
        // We need to do some effort here: copy all 
        //
        return valueMeta.convertData(convertMeta, pol); 
    }
            
    /*
	public static final Object convertValue( String pol, String field_name, int field_type, String field_format, 
			                                int field_length, int field_precision, String num_group, String num_decimal, 
			                                String num_currency, String nullif, String ifNull, int trim_type,
			                                DecimalFormat ldf, DecimalFormatSymbols ldfs, 
			                                SimpleDateFormat ldaf, DateFormatSymbols ldafs
			                               ) throws Exception
	{
		Object value=null;

		if (Const.isEmpty(pol) || pol.equalsIgnoreCase(ifNull))
		{
            if (ifNull!=null && ifNull.length()!=0)
                pol = ifNull;
		}
        
        if (pol == null || pol.length() == 0 || pol.equalsIgnoreCase(nullif)) 
        {
            value=null;
        }
        else
		{
			if (field_type==ValueMetaInterface.TYPE_NUMBER || field_type==ValueMetaInterface.TYPE_INTEGER || field_type==ValueMetaInterface.TYPE_BIGNUMBER)
			{
				try
				{
					

					switch (trim_type)
					{
					case TextFileInputMeta.TYPE_TRIM_LEFT:
                        {
                            StringBuffer strpol = new StringBuffer(pol);
    						while (strpol.length() > 0 && strpol.charAt(0) == ' ')
    							strpol.deleteCharAt(0);
                            pol=strpol.toString();
                        }
						break;
					case TextFileInputMeta.TYPE_TRIM_RIGHT:
                        {
                            StringBuffer strpol = new StringBuffer(pol);
                            while (strpol.length() > 0 && strpol.charAt(strpol.length() - 1) == ' ')
    							strpol.deleteCharAt(strpol.length() - 1);
                            pol=strpol.toString();
                        }
                        break;
					case TextFileInputMeta.TYPE_TRIM_BOTH:
                        StringBuffer strpol = new StringBuffer(pol);
                        {
    						while (strpol.length() > 0 && strpol.charAt(0) == ' ')
    							strpol.deleteCharAt(0);
    						while (strpol.length() > 0 && strpol.charAt(strpol.length() - 1) == ' ')
    							strpol.deleteCharAt(strpol.length() - 1);
                            pol=strpol.toString();
                        }
						break;
					default:
						break;
					}

                    switch(field_type)
                    {
                    case ValueMetaInterface.TYPE_NUMBER:
    					{
    						if (field_format != null)
    						{
    							ldf.applyPattern(field_format);
    
    							if (num_decimal != null && num_decimal.length() >= 1) ldfs.setDecimalSeparator(num_decimal.charAt(0));
    							if (num_group != null && num_group.length() >= 1) ldfs.setGroupingSeparator(num_group.charAt(0));
    							if (num_currency != null && num_currency.length() >= 1) ldfs.setCurrencySymbol(num_currency);
    
    							ldf.setDecimalFormatSymbols(ldfs);
    						}
    
    						value = new Double( ldf.parse(pol).doubleValue() );
    					}
                        break;
                    case ValueMetaInterface.TYPE_INTEGER:
						{
							value = new Long( Long.parseLong(pol) );
						}
                        break;
                    case ValueMetaInterface.TYPE_BIGNUMBER:
						{
							value = new BigDecimal(pol);
						}
                        break;
                    default: 
						throw new KettleValueException("Unknown numeric type: this is a program error.");
					}
				}
				catch (Exception e)
				{
					throw (e);
				}
			}
			else
			{
				if (field_type==ValueMetaInterface.TYPE_STRING)
				{
                    if (pol.length() == 0)
                    {
                        value=null;
                    }
                    else
                    {
    					switch (trim_type)
    					{
    					case TextFileInputMeta.TYPE_TRIM_LEFT:
    						value=ValueDataUtil.leftTrim(pol);
    						break;
    					case TextFileInputMeta.TYPE_TRIM_RIGHT:
                            value=ValueDataUtil.rightTrim(pol);
    						break;
    					case TextFileInputMeta.TYPE_TRIM_BOTH:
    						value=ValueDataUtil.trim(pol);
    						break;
    					default:
    						value=pol;
    					}
                    }
					
				}
				else
				{
					if (field_type==ValueMetaInterface.TYPE_DATE)
					{
						try
						{
							if (field_format != null)
							{
								ldaf.applyPattern(field_format);
								ldaf.setDateFormatSymbols(ldafs);
							}

							value = ldaf.parse(pol);
						}
						catch (Exception e)
						{
							throw (e);
						}
					}
					else
					{
						if (field_type==ValueMetaInterface.TYPE_BINARY)
						{
						    value = pol.getBytes();	
						}
					}	
				}
			}
		}
		
		return value;
	}
�   */
    
	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException
	{
		Object[] r = null;
		boolean retval = true;
		boolean putrow = false;

		if (first) // we just got started
		{
            first = false;
            
            // Create the output row metadata
            data.outputRowMeta = new RowMeta();
            meta.getFields(data.outputRowMeta, getStepname(), null, null, this); // get the metadata populated.  Simple and easy.
            
            // Create convertor metadata objects that will contain Date & Number formatters
            //
            data.convertRowMeta = (RowMetaInterface)data.outputRowMeta.clone();
            for (int i=0;i<data.convertRowMeta.size();i++) data.convertRowMeta.getValueMeta(i).setType(ValueMetaInterface.TYPE_STRING);

            if (meta.isAcceptingFilenames())
            {
                // Read the files from the specified input stream...
                data.files.getFiles().clear();
                
                int idx = -1;
                RowMetaAndData fileRow = getRowFrom(meta.getAcceptingStepName());
                while (fileRow!=null)
                {
                    if (idx<0)
                    {
                        idx = fileRow.getRowMeta().indexOfValue(meta.getAcceptingField());
                        if (idx<0)
                        {
                            logError(Messages.getString("TextFileInput.Log.Error.UnableToFindFilenameField", meta.getAcceptingField()));
                            setErrors(1);
                            stopAll();
                            return false;
                        }
                    }
                    String fileValue = fileRow.getRowMeta().getString(fileRow.getData(), idx);
                    try
                    {
                        FileObject fileObject = KettleVFS.getFileObject(fileValue);
                        data.files.addFile(fileObject);
                    }
                    catch(IOException e)
                    {
                        logError(Messages.getString("TextFileInput.Log.Error.UnableToCreateFileObject", fileValue));
                    }
                    
                    // Grab another row
                    fileRow = getRowFrom(meta.getAcceptingStepName());
                }
                
                if (data.files.nrOfFiles()==0)
                {
                    logBasic(Messages.getString("TextFileInput.Log.Error.NoFilesSpecified"));
                    setOutputDone();
                    return false;
                }
            }
            handleMissingFiles();
            
			// Open the first file & read the required rows in the buffer, stop
			// if it fails...
			if (!openNextFile())
			{
				closeLastFile();
				setOutputDone();
				return false;
			}

			// Count the number of repeat fields...
			for (int i = 0; i < meta.getInputFields().length; i++)
			{
				if (meta.getInputFields()[i].isRepeated()) data.nr_repeats++;
			}
		}
		else
		{
			if (!data.doneReading)
			{
				int repeats = 1;
				if (meta.isLineWrapped()) repeats = meta.getNrWraps() > 0 ? meta.getNrWraps() : repeats;

				// Read a number of lines...
				for (int i = 0; i < repeats && !data.doneReading; i++)
				{
					String line = getLine(log, data.isr, data.fileFormatType, data.lineStringBuffer); // Get one line of data;
					if (line != null)
					{
						// Filter row?
						boolean isFilterLastLine = false;
						boolean filterOK = checkFilterRow(line, isFilterLastLine);
						if (filterOK)
						{
							// logRowlevel("LINE READ: "+line);
							data.lineBuffer.add(new TextFileLine(line, lineNumberInFile, data.file));
						} 
						else
						{
							if (isFilterLastLine)
							{
								data.doneReading = true;
							}
							repeats++; // grab another line, this one got filtered
						}
					}
					else
					{
						data.doneReading = true;
					}
				}
			}
		}

		/* If the buffer is empty: open the next file. 
		 * (if nothing in there, open the next, etc.)
		 */
		while (data.lineBuffer.size() == 0)
		{
			if (!openNextFile()) // Open fails: done processing!
			{
				closeLastFile();
				setOutputDone(); // signal end to receiver(s)
				return false;
			}
		}

		/* Take the first line available in the buffer & remove the line from
		   the buffer
		*/
		TextFileLine textLine = (TextFileLine) data.lineBuffer.get(0);
		linesInput++;
		lineNumberInFile++;

		data.lineBuffer.remove(0);

		if (meta.isLayoutPaged())
		{
			/* Different rules apply: on each page:
			   a header
			   a number of data lines
			   a footer
			*/ 
			if (!data.doneWithHeader && data.pageLinesRead == 0) // We are reading header lines
			{
				if (log.isRowLevel()) logRowlevel("P-HEADER (" + data.headerLinesRead + ") : " + textLine.line);
				data.headerLinesRead++;
				if (data.headerLinesRead >= meta.getNrHeaderLines())
				{
					data.doneWithHeader = true;
				}
			}
			else
			// data lines or footer on a page
			{
				if (data.pageLinesRead < meta.getNrLinesPerPage())
				{
					// See if we are dealing with wrapped lines:
					if (meta.isLineWrapped())
					{
						for (int i = 0; i < meta.getNrWraps(); i++)
						{
							String extra = "";
							if (data.lineBuffer.size() > 0)
							{
								extra = ((TextFileLine) data.lineBuffer.get(0)).line;
								data.lineBuffer.remove(0);
							}
							textLine.line += extra;
						}
					}

					if (log.isRowLevel()) logRowlevel("P-DATA: " + textLine.line);
					// Read a normal line on a page of data.
					data.pageLinesRead++;
					data.lineInFile ++;
					long useNumber = meta.isRowNumberByFile() ? data.lineInFile : linesWritten + 1;
					r = convertLineToRow(textLine, meta, data.outputRowMeta, data.convertRowMeta, data.filename, useNumber, data.dataErrorLineHandler);
					if (r != null) putrow = true;
				}
				else
				// done reading the data lines, skip the footer lines
				{
					if (meta.hasFooter() && data.footerLinesRead < meta.getNrFooterLines())
					{
						if (log.isRowLevel()) logRowlevel("P-FOOTER: " + textLine.line);
						data.footerLinesRead++;
					}

					if (!meta.hasFooter() || data.footerLinesRead >= meta.getNrFooterLines())
					{
						/* OK, we are done reading the footer lines, start again
						   on 'next page' with the header
						 */
						data.doneWithHeader = false;
						data.headerLinesRead = 0;
						data.pageLinesRead = 0;
						data.footerLinesRead = 0;
						if (log.isRowLevel()) logRowlevel("RESTART PAGE");
					}
				}
			}
		}
		else
		// A normal data line, can also be a header or a footer line
		{
			if (!data.doneWithHeader) // We are reading header lines
			{
				data.headerLinesRead++;
				if (data.headerLinesRead >= meta.getNrHeaderLines())
				{
					data.doneWithHeader = true;
				}
			}
			else
			{
				/* IF we are done reading and we have a footer
				   AND the number of lines in the buffer is smaller then the number of footer lines
				   THEN we can remove the remaining rows from the buffer: they are all footer rows.
				 */
				if (data.doneReading && meta.hasFooter() && data.lineBuffer.size() < meta.getNrFooterLines())
				{
					data.lineBuffer.clear();
				}
				else
				// Not yet a footer line: it's a normal data line.
				{
					// See if we are dealing with wrapped lines:
					if (meta.isLineWrapped())
					{
						for (int i = 0; i < meta.getNrWraps(); i++)
						{
							String extra = "";
							if (data.lineBuffer.size() > 0)
							{
								extra = ((TextFileLine) data.lineBuffer.get(0)).line;
								data.lineBuffer.remove(0);
							}
							textLine.line += extra;
						}
					}
					if (data.filePlayList.isProcessingNeeded(textLine.file, textLine.lineNumber, AbstractFileErrorHandler.NO_PARTS))
					{
						data.lineInFile ++;
						long useNumber = meta.isRowNumberByFile() ? data.lineInFile : linesWritten + 1;
						r = convertLineToRow(textLine, meta, data.outputRowMeta, data.convertRowMeta, data.filename, useNumber, data.dataErrorLineHandler);
						if (r != null)
						{
							// System.out.println("Found data row: "+r);
							putrow = true;
						}
					}
					else putrow = false;
				}
			}
		}

		if (putrow && r != null)
		{
			// See if the previous values need to be repeated!
			if (data.nr_repeats > 0)
			{
				if (data.previous_row == null) // First invocation...
				{
					data.previous_row = data.outputRowMeta.cloneRow(r);
				}
				else
				{
					int repnr = 0;
					for (int i = 0; i < meta.getInputFields().length; i++)
					{
						if (meta.getInputFields()[i].isRepeated())
						{
							if (r[i]==null) // if it is empty: take the previous value!
							{
								r[i] = data.previous_row[i];
							}
							else
							// not empty: change the previous_row entry!
							{
								data.previous_row[i] = r[i];
							}
							repnr++;
						}
					}
				}
			}

			if (log.isRowLevel()) logRowlevel("Putting row: " + r.toString());			
			putRow(data.outputRowMeta, r);

			if ( linesInput >= meta.getRowLimit() && meta.getRowLimit() >0 )
			{
			    closeLastFile();
			    setOutputDone(); // signal end to receiver(s)
			    return false;
			}
		}

        if (checkFeedback(linesInput)) logBasic("linenr " + linesInput);

		return retval;
	}

    /**
	 * Check if the line should be taken.
	 * @param line
	 * @param isFilterLastLine (dummy input param, only set when return value is false)
	 * @return true when the line should be taken (when false, isFilterLastLine will be set)
	 */
	private boolean checkFilterRow(String line, boolean isFilterLastLine) {
		boolean filterOK=true;
		
		// check for noEmptyLines
		if (meta.noEmptyLines() && line.length() == 0)
		{
			filterOK=false;
		} else {
			// check the filters
			filterOK = data.filterProcessor.doFilters(line);
			if ( ! filterOK )
			{
				if ( data.filterProcessor.isStopProcessing())
				{
				    data.doneReading = true;
				}
			}
		}
		
		return filterOK;
	}

	private void handleMissingFiles() throws KettleException
	{
		List<FileObject> nonExistantFiles = data.files.getNonExistantFiles();

		if (nonExistantFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonExistantFiles);
			log.logBasic("Required files", "WARNING: Missing " + message);
			if (meta.isErrorIgnored()) {
				for (FileObject fileObject : nonExistantFiles) {
					data.dataErrorLineHandler.handleNonExistantFile(fileObject);
				}
			}
			else {
				throw new KettleException("Following required files are missing: " + message);
			}
		}

		List<FileObject> nonAccessibleFiles = data.files.getNonAccessibleFiles();
		if (nonAccessibleFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonAccessibleFiles);
			log.logBasic("Required files", "WARNING: Not accessible " + message);
			if (meta.isErrorIgnored()) {
				for (FileObject fileObject : nonAccessibleFiles) {
					data.dataErrorLineHandler.handleNonAccessibleFile(fileObject);
				}
			} else {
				throw new KettleException("Following required files are not accessible: " + message);
			}
		}
	}

	private boolean closeLastFile()
	{
		try
		{
			// Close previous file!
			if (data.filename != null)
			{
                String sFileCompression = meta.getFileCompression();
				if (sFileCompression != null && sFileCompression.equals("Zip"))
				{
					data.zi.closeEntry();
					data.zi.close();
				}
				else if (sFileCompression != null && sFileCompression.equals("GZip"))
				{
					data.gzi.close();
				}
				data.fr.close();
				data.isr.close();
				data.filename = null; // send it down the next time.
				if ( data.file != null )
				{
					data.file.close();
					data.file = null;
				}
			}
			data.dataErrorLineHandler.close();
		}
		catch (Exception e)
		{
			logError("Couldn't close file : " + data.filename + " --> " + e.toString());
			stopAll();
			setErrors(1);
			return false;
		}

		return !data.isLastFile;
	}

	private boolean openNextFile()
	{
		try
		{
			lineNumberInFile = 0;
			if (!closeLastFile()) return false;

			if (data.files.nrOfFiles() == 0) return false;

			// Is this the last file?
			data.isLastFile = (data.filenr == data.files.nrOfFiles() - 1);
			data.file = data.files.getFile(data.filenr);
			data.filename = KettleVFS.getFilename( data.file );
			data.lineInFile = 0;
			
            // Add this files to the result of this transformation.
            //
			ResultFile resultFile = new ResultFile(ResultFile.FILE_TYPE_GENERAL, data.file, getTransMeta().getName(), toString());
			addResultFile(resultFile);

			logBasic("Opening file: " + data.filename);

			data.fr = KettleVFS.getInputStream(data.file);
			data.dataErrorLineHandler.handleFile(data.file);

            String sFileCompression = meta.getFileCompression();
			if (sFileCompression != null && sFileCompression.equals("Zip"))
			{
				logBasic("This is a zipped file");
				data.zi = new ZipInputStream(data.fr);
				data.zi.getNextEntry();

				if (meta.getEncoding() != null && meta.getEncoding().length() > 0)
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.zi, BUFFER_SIZE_INPUT_STREAM), meta.getEncoding());
				}
				else
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.zi, BUFFER_SIZE_INPUT_STREAM));
				}
			}
			else if (sFileCompression != null && sFileCompression.equals("GZip"))
			{
				logBasic("This is a gzipped file");
				data.gzi = new GZIPInputStream(data.fr);

				if (meta.getEncoding() != null && meta.getEncoding().length() > 0)
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.gzi, BUFFER_SIZE_INPUT_STREAM), meta.getEncoding());
				}
				else
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.gzi, BUFFER_SIZE_INPUT_STREAM));
				}
			}
			else
			{
				if (meta.getEncoding() != null && meta.getEncoding().length() > 0)
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.fr, BUFFER_SIZE_INPUT_STREAM), meta.getEncoding());
				}
				else
				{
					data.isr = new InputStreamReader(new BufferedInputStream(data.fr, BUFFER_SIZE_INPUT_STREAM));
				}
			}

			// Move file pointer ahead!
			data.filenr++;

			// /////////////////////////////////////////////////////////////////////////////
			// Read the first lines...

			/* Keep track of the status of the file: are there any lines left to read?
			 */
			data.doneReading = false;

			/* OK, read a number of lines in the buffer:
			   The header rows
			   The nr rows in the page : optional
			   The footer rows
			 */
			int bufferSize = 1; // try to read at least one line.
			bufferSize += meta.hasHeader() ? meta.getNrHeaderLines() : 0;
			bufferSize += meta.isLayoutPaged() ? meta.getNrLinesPerPage() : 0;
			bufferSize += meta.hasFooter() ? meta.getNrFooterLines() : 0;

			// See if we need to skip the document header lines...
			if (meta.isLayoutPaged())
			{
				for (int i = 0; i < meta.getNrLinesDocHeader(); i++)
				{
					// Just skip these...
					getLine(log, data.isr, data.fileFormatType, data.lineStringBuffer); // header and footer: not wrapped
					lineNumberInFile++;
				}
			}

			String line;
			for (int i = 0; i < bufferSize && !data.doneReading; i++)
			{
				line = getLine(log, data.isr, data.fileFormatType, data.lineStringBuffer);
				if (line != null)
				{
					// when there is no header, check the filter for the first line
					if (!meta.hasHeader())
					{
						// Filter row?
						boolean isFilterLastLine = false;
						boolean filterOK = checkFilterRow(line, isFilterLastLine);
						if (filterOK)
						{
							data.lineBuffer.add(new TextFileLine(line, lineNumberInFile, data.file)); // Store it in the
							// line buffer...
						} 
						else
						{
							bufferSize++; // grab another line, this one got filtered
						}
					}
					else //there is a header, so don't checkFilterRow
					{
						if (!meta.noEmptyLines() || line.length() != 0)
						{
						    data.lineBuffer.add(new TextFileLine(line, lineNumberInFile, data.file)); // Store it in the line buffer...
						}						
					}
				}
				else
				{
					data.doneReading = true;
				}
			}

			// Reset counters etc.
			data.headerLinesRead = 0;
			data.footerLinesRead = 0;
			data.pageLinesRead = 0;

			// Set a flags
			data.doneWithHeader = !meta.hasHeader();
		}
		catch (Exception e)
		{
			logError("Couldn't open file #" + data.filenr + " : " + data.filename + " --> " + e.toString());
			stopAll();
			setErrors(1);
			return false;
		}
		return true;
	}

	public boolean init(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta = (TextFileInputMeta) smi;
		data = (TextFileInputData) sdi;

		if (super.init(smi, sdi))
		{
			initErrorHandling();
			initReplayFactory();
				
			data.files = meta.getTextFileList(this);
			data.filterProcessor = new TextFileFilterProcessor(meta.getFilter());
            
            // If there are missing files, fail if we don't ignore errors
            //
			if ( (transmeta.getPreviousResult()==null || transmeta.getPreviousResult().getResultFiles()==null || transmeta.getPreviousResult().getResultFiles().size()==0) && 
                  data.files.nrOfMissingFiles() > 0 && !meta.isAcceptingFilenames() && !meta.isErrorIgnored()
               )
			{
				logError(Messages.getString("TextFileInput.Log.Error.NoFilesSpecified"));
				return false;
			}
            
            String nr = getVariable(Const.INTERNAL_VARIABLE_SLAVE_TRANS_NUMBER);
            if (!Const.isEmpty(nr))
            {
                // TODO: add metadata to configure this.
                logBasic("Running on slave server #"+nr+" : assuming that each slave reads a dedicated part of the same file(s)."); 
            }
            
            // If no nullif field is supplied, take the default.
            // String null_value = nullif;
            // if (null_value == null)
            // {
            // //     value="";
            // }
            // String null_cmp = Const.rightPad(new StringBuffer(null_value), pol.length());

            // calculate the file format type in advance so we can use a switch
            data.fileFormatType = meta.getFileFormatTypeNr();

            // calculate the file type in advance CSV or Fixed?
            data.fileType = meta.getFileTypeNr();
                
			return true;
		}
		return false;
	}

	private void initReplayFactory()
	{
		Date replayDate = getTrans().getReplayDate();
		if (replayDate == null) data.filePlayList = FilePlayListAll.INSTANCE;
		else data.filePlayList = new FilePlayListReplay(replayDate, meta.getLineNumberFilesDestinationDirectory(),
				meta.getLineNumberFilesExtension(), meta.getErrorFilesDestinationDirectory(), meta.getErrorLineFilesExtension(), meta.getEncoding());
	}

	private void initErrorHandling()
	{
		List<FileErrorHandler> dataErrorLineHandlers = new ArrayList<FileErrorHandler>(2);
		if (meta.getLineNumberFilesDestinationDirectory() != null)
			dataErrorLineHandlers.add(new FileErrorHandlerContentLineNumber(getTrans().getCurrentDate(), meta
					.getLineNumberFilesDestinationDirectory(), meta.getLineNumberFilesExtension(), meta.getEncoding(), this));
		if (meta.getErrorFilesDestinationDirectory() != null)
			dataErrorLineHandlers.add(new FileErrorHandlerMissingFiles(getTrans().getCurrentDate(), meta.getErrorFilesDestinationDirectory(), meta
					.getErrorLineFilesExtension(), meta.getEncoding(), this));
		data.dataErrorLineHandler = new CompositeFileErrorHandler(dataErrorLineHandlers);
	}

	public void dispose(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta = (TextFileInputMeta) smi;
		data = (TextFileInputData) sdi;

		super.dispose(smi, sdi);
	}

	//
	// Run is were the action happens!
	//
	//
	public void run()
	{
		try
		{
			logBasic("Starting to run...");
			while (processRow(meta, data) && !isStopped())
				;
		}
		catch (Exception e)
		{
			logError("Unexpected error : " + e.toString());
            logError(Const.getStackTracker(e));
            setErrors(1);
			stopAll();
		}
		finally
		{
			dispose(meta, data);
			logSummary();
			markStop();
		}
	}
}