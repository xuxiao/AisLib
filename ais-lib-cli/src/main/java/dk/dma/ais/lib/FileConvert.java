/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.dma.ais.lib;

import com.beust.jcommander.Parameter;
import com.google.inject.Injector;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.packet.AisPacketOutputSinks;
import dk.dma.ais.packet.AisPacketReader;
import dk.dma.commons.app.AbstractCommandLineTool;
import dk.dma.commons.util.io.OutputStreamSink;
import dk.dma.enav.util.function.EConsumer;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts a set of files into another sink format
 *
 * @author Jens Tuxen
 * @author Thomas Borg Salling
 *
 */
public class FileConvert extends AbstractCommandLineTool {

    /** The logger. */
    static final Logger LOG = LoggerFactory.getLogger(FileConvert.class);

    @Parameter(required = true, description = "files to import...")
    List<String> sources;

    /** Where files should be moved to after having been processed. */
    @Parameter(names = "-path", required = false, description = "base-path to put converted files")
    String convertTo = "converted/";

    /** Where files should be moved to after having been processed. */
    @Parameter(names = "-keepStructure", required = false, description = "Whether to keep path structure")
    boolean keepFileStructure = true;

    @Parameter(names = "-fileEnding", required = false, description = "File ending")
    String fileEnding;

    @Parameter(names = "-outputFormat", required = false, description = "Output formats: [OUTPUT_TO_TEXT, OUTPUT_PREFIXED_SENTENCES, OUTPUT_TO_HTML, table, csv, csv_stateful]")
    String outputSinkFormat = "OUTPUT_PREFIXED_SENTENCES";

    @Parameter(names = "-columns", required = false, description = "Optional columns, required with -outputFormat table. use ; as delimiter. Example: -columns mmsi;time;lat;lon")
    String columns;

    public FileConvert() {
    }

    /**
     * getOutputSinks
     *
     * @return
     */
    static final List<String> getOutputSinks() {
        LinkedList<String> methods = new LinkedList<String>();
        Field[] declaredMethods = AisPacketOutputSinks.class.getDeclaredFields();
        for (Field m : declaredMethods) {
            if (m.getName().startsWith("OUTPUT")) {
                methods.add(m.getName());
            }
        }
        return methods;
    }

    /** {@inheritDoc} */
    @Override
    protected void run(Injector injector) throws Exception {
        configureFileEnding();

        // final long start = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger();

        final EConsumer<String> consumer = new EConsumer<String>() {

            @Override
            public void accept(String s) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
                    SecurityException, IOException, InterruptedException {
                Path path = Paths.get(s);
                LOG.debug("Started processing file " + path);

                Path endPath;
                if (keepFileStructure) {
                    Path relative;
                    relative = path;
                    endPath = Paths.get(Paths.get(convertTo).toString(), relative.toString());
                    new File(endPath.toString()).mkdirs();
                } else {
                    endPath = Paths.get(convertTo);
                }

                String filename = path.getFileName().toString();
                if (! filename.endsWith(fileEnding))
                    filename = FilenameUtils.removeExtension(filename) + fileEnding;
                Path filePath = Paths.get(endPath.toString(), filename);

                LOG.debug("Output File: " + filePath.toString());

                final OutputStream fos = new FileOutputStream(filePath.toString()); // 2

                final OutputStreamSink<AisPacket> sink = AisPacketOutputSinks.getOutputSink(outputSinkFormat, columns);
                sink.closeWhenFooterWritten();

                AisPacketReader apis = AisPacketReader.createFromFile(path, false);

                apis.writeTo(fos, sink);
                apis.close();
                fos.close();
            }
        };

        /*
         * Creates a pool of executors, 4 threads. Each thread will open a file using an aispacket reader, 10000 files can be
         * submitted to the queue, afterwards the calling thread will execute the job instead.
         */
        ThreadPoolExecutor threadpoolexecutor = new ThreadPoolExecutor(4, 4, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(
                10000), new ThreadPoolExecutor.CallerRunsPolicy());
        for (final String s : sources) {
            threadpoolexecutor.execute(() -> {
                try {
                    consumer.accept(s);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            });
        }

        threadpoolexecutor.shutdown();
        threadpoolexecutor.awaitTermination(999, TimeUnit.DAYS);
    }

    private void configureFileEnding() {
        if (StringUtils.isBlank(fileEnding)) {
            if (outputSinkFormat.startsWith("csv")) {
                fileEnding = ".csv";
            } else {
                fileEnding = ".txt";
            }
        }
    }

    public static void main(String[] args) throws Exception {
        FileConvert fc = new FileConvert();
        fc.execute(args);
    }
}
