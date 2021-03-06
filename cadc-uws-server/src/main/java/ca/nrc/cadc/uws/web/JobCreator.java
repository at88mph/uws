
package ca.nrc.cadc.uws.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Enumeration;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.log4j.Logger;

import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.net.NetUtil;
import ca.nrc.cadc.util.StringUtil;
import ca.nrc.cadc.uws.ExecutionPhase;
import ca.nrc.cadc.uws.Job;
import ca.nrc.cadc.uws.JobAttribute;
import ca.nrc.cadc.uws.Parameter;
import ca.nrc.cadc.rest.SyncInput;
import ca.nrc.cadc.uws.JobInfo;
import java.util.Arrays;
import java.util.List;

/**
 * Simple class used to read job description from the request and create a new Job.

 * @author pdowler
 */
public class JobCreator 
{
    private static final Logger log = Logger.getLogger(JobCreator.class);

    protected static final DateFormat dateFormat = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);

    protected static final String URLENCODED = "application/x-www-form-urlencoded";
    protected static final String TEXT_XML = "text/xml";
    protected static final String MULTIPART = "multipart/form-data";

    public JobCreator() {
    }

    // cadc-rest based create method
    public Job create(SyncInput input) {
        Job job = new Job();
        job.setExecutionPhase(ExecutionPhase.PENDING);
        job.setParameterList(new ArrayList<Parameter>());
        for (String pname : input.getParameterNames()) {
            if (JobAttribute.isValue(pname)) {
                processUWSParameter(job, pname, input.getParameter(pname));
            } else {
                processJobParameter(job, pname, input.getParameters(pname));
            }
        }
        
        for (String cname : input.getContentNames()) {
            if (UWSInlineContentHandler.CONTENT_JOBINFO.equals(cname)) {
                JobInfo ji = (JobInfo) input.getContent(cname);
                job.setJobInfo(ji);
            } else if (UWSInlineContentHandler.CONTENT_PARAM_REPLACE.equals(cname)) {
                UWSInlineContentHandler.ParameterReplacement pr = (UWSInlineContentHandler.ParameterReplacement) input.getContent(cname);
                boolean replaced = false;
                for (Parameter p : job.getParameterList()) {
                    String ostr = p.getValue();
                    String nstr = ostr.replace(pr.origStr, pr.newStr);
                    if (!ostr.equals(nstr)) {
                        log.debug("replace param: " + p.getName() + ": " + ostr + " -> " + nstr);
                        p.setValue(nstr);
                        replaced = true;
                    }
                }
                if (!replaced) {
                    log.debug("no parameter modified by " + pr);
                }
            } else {
                log.debug("inline content: " + cname + " -> new param");
                // assume content -> param key=value
                Object val = input.getContent(cname);
                if (val != null) {
                    job.getParameterList().add(new Parameter(cname, val.toString()));
                }
            }
        }
        
        job.setRequestPath(input.getRequestPath());
        job.setRemoteIP(input.getClientIP());
        return job;
    }
    
    // old restlet based create method
    @Deprecated
    public Job create(HttpServletRequest request, InlineContentHandler inlineContentHandler)
        throws FileUploadException, IOException
    {
        Job job = new Job();
        job.setExecutionPhase(ExecutionPhase.PENDING);
        job.setParameterList(new ArrayList<Parameter>());

        if (request.getMethod().equals("GET"))
        {
            Enumeration<String> names = request.getParameterNames();
            while (names.hasMoreElements())
            {
                String name = names.nextElement();
                processParameter(job, name, request.getParameterValues(name));
            }
        }
        else
        {
            String contentType = request.getContentType();
            if (contentType != null)
            {
                int i = contentType.indexOf(';');
                if (i > 0)
                    contentType = contentType.substring(0, i);
            }
            log.debug("Content-Type: " + contentType);
            if (contentType != null && contentType.equalsIgnoreCase(URLENCODED))
            {
                Enumeration<String> names = request.getParameterNames();
                while (names.hasMoreElements())
                {
                    String name = names.nextElement();
                    processParameter(job, name, request.getParameterValues(name));
                }
            }
            else if (inlineContentHandler != null)
            {
                if (contentType != null && contentType.startsWith(MULTIPART))
                {
                    ServletFileUpload upload = new ServletFileUpload();
                    FileItemIterator itemIterator = upload.getItemIterator(request);
                    processMultiPart(job, itemIterator, inlineContentHandler);
                }
                else
                {
                    processStream(null, contentType, request.getInputStream(), inlineContentHandler);
                }

                inlineContentHandler.setParameterList(job.getParameterList());
                job.setParameterList(inlineContentHandler.getParameterList());
                job.setJobInfo(inlineContentHandler.getJobInfo());
            }
        }

        try
        {
            URL u = new URL(request.getRequestURL().toString());
            job.setRequestPath(u.getPath());
        }
        catch(MalformedURLException oops)
        {
            log.error("failed to get request path", oops);
        }
        
        job.setRemoteIP(NetUtil.getClientIP(request));
        return job;
    }
    
    protected void processParameter(Job job, String name, String[] values)
    {
        if (JobAttribute.isValue(name)) {
            if (values != null && values.length > 0) {
                processUWSParameter(job, name, values[0]);
            }
        } else {
            processJobParameter(job, name, Arrays.asList(values));
        }
    }

    private void processUWSParameter(Job job, String name, String value)
    {
        if (name.equalsIgnoreCase(JobAttribute.RUN_ID.getAttributeName()))
        {
            job.setRunID(value);
        }
        else if (name.equalsIgnoreCase(JobAttribute.DESTRUCTION_TIME.getAttributeName()))
        {
            if (StringUtil.hasText(value))
            {
                try
                {
                    job.setDestructionTime(dateFormat.parse(value));
                }
                catch (ParseException e)
                {
                    log.error("Cannot parse Destruction Time to IVOA date format " + value, e);
                    throw new IllegalArgumentException("Cannot parse Destruction Time to IVOA date format " + value, e);
                }
            }
            else
            {
                job.setDestructionTime(null);
            }
        }
        else if(name.equalsIgnoreCase(JobAttribute.EXECUTION_DURATION.getAttributeName()))
        {
            if (StringUtil.hasText(value))
                job.setExecutionDuration(Long.parseLong(value));
        }
        else if (name.equalsIgnoreCase(JobAttribute.QUOTE.getAttributeName()))
        {
            if (StringUtil.hasText(value))
            {
                try
                {
                    job.setQuote(dateFormat.parse(value));
                }
                catch (ParseException e)
                {
                    log.error("Cannot parse Quote to IVOA date format " + value, e);
                    throw new IllegalArgumentException("Cannot parse Quote to IVOA date format " + value, e);
                }
            }
            else
            {
                job.setQuote(null);
            }
        }
    }

    protected void processJobParameter(Job job, String name, Iterable<String> values)
    {
        for (String value : values) {
            job.getParameterList().add(new Parameter(name, value));
        }
    }

    protected void processMultiPart(Job job, FileItemIterator itemIterator, InlineContentHandler ich)
        throws FileUploadException, IOException
    {
        while (itemIterator.hasNext())
        {
            FileItemStream item = itemIterator.next();
            String name = item.getFieldName();
            InputStream stream = item.openStream();
            if (item.isFormField())
                processParameter(job, name, new String[] { Streams.asString(stream) });
            else
                processStream(name, item.getContentType(), stream, ich);
        }
    }

    protected void processStream(String name, String contentType, InputStream inputStream, InlineContentHandler ich)
        throws IOException
    {
        ich.accept(name, contentType, inputStream);
    }

}
