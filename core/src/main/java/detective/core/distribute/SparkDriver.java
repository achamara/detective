package detective.core.distribute;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import detective.core.distribute.collector.JobCollector;
import detective.core.services.DetectiveFactory;

public class SparkDriver {

  private final static Logger logger = LoggerFactory.getLogger(SparkDriver.class);
  
  private final static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
  
  public static String getDefaultAppName(String packageOrClassName){
    return "Detective-" + packageOrClassName + "-" + format.format(new Date());
  }
  
  /**
   * ./bin/spark-submit --class detective.core.distribute.SparkDriver --master spark://127.0.0.1:7000 your.jar spark://127.0.0.1:7000
   * 
   * @param args classOrPackageName
   * @return
   */
  public static void main(String[] args) {
    if (args == null || args.length < 1){
      System.err.println("Usage: detective.core.distribute.SparkDriver packageOrClassName applicationName(Optional)\n"
          + "  if you'd like to setup master, please change your detective.conf file or -Dspark.master=yourMasterUrl\n"
          + "  -Dspark.driver.host is the host name if you'd like to use");
      System.exit(-1);
    }
    
    long errors = run(args);
    
    if (errors > 0)
      System.exit(-1);
  }

  /**
   * 
   * @param args
   * @return how many jobs failed
   */
  public static long run(String[] args) {
    String master = DetectiveFactory.INSTANCE.getConfig().getString("spark.master");
    logger.info("Detective is running with master " + master);
    String packageName = args[0];
    
    String appName = null;
    if (args.length >= 2){
      appName = args[1];
    }else
      appName = getDefaultAppName(packageName);
    
    SparkConf sparkConf = new SparkConf()    
      .setAppName(appName)
      .setMaster(master)
      //.setMaster("local[8]")
      //.setMaster("spark://127.0.0.1:7000")
      //.set("spark.driver.host", "localhost")
      //.set("spark.driver.port", "5555")
      ;
    JavaSparkContext jsc = new JavaSparkContext(sparkConf);

    int duplicatedtasks = DetectiveFactory.INSTANCE.getConfig().getInt("spark.pressureTest.duplicateTasks");
    logger.info("Detective pressure test, jobs duplication: " + duplicatedtasks);
    List<JobToRun> jobs = JobCollector.collectAll(packageName, duplicatedtasks);
    
    Long startTime = System.nanoTime();
    
    JavaRDD<JobToRun> dataset = jsc.parallelize(jobs, jobs.size());
    
    JavaRDD<JobRunResult> datasetResult = dataset.map(new Function<JobToRun, JobRunResult>(){

      @Override
      public JobRunResult call(JobToRun job) throws Exception {
        logger.info("Running Job:" + job.toString());
        
        try {
          JobRunner runner = new JobRunnerFilterImpl();
          runner.run(job);
          return job.getJobResult();
        } catch (Throwable e) {
          //We never throw a exception into spark as we don't want it retry
          logger.error(e.getMessage(), e);
          JobRunResult result = new JobRunResult();
          result.setStoryName(job.getStoryClassName());
          result.setScenarioName("ScenarioIndex:" + job.getScenarioIndex());
          result.setError(e);
          result.setSuccessed(false);
          return result;
        }
      }});
    
    List<JobRunResult> jobsAfterRun = datasetResult.collect();
    
    Long endTime = System.nanoTime();
    
    Collections.sort(jobsAfterRun);
    long errors = 0;
    long skipped = 0;
    for (JobRunResult job : jobsAfterRun){
      if (! job.getSuccessed())
        errors = errors + 1;
      if (job.isIgnored())
        skipped ++;
      
      logger.info(job.toString());      
    }
    
    Long timeElapsedSec = TimeUnit.SECONDS.convert(endTime - startTime, TimeUnit.NANOSECONDS);
    logger.info("Tests run: "+jobsAfterRun.size()+", Errors: "+errors+", Skipped: "+skipped+", Time elapsed: " + timeElapsedSec + " Seconds ");
    return errors;
  }
  


}
