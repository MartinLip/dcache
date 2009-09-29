#
#  defined :
#     ourBaseName
#     setupFilePath
#     thisDir
#     ourHomeDir
#     config
#     pidDir
#
if [ ! -z "${pool}" ] ; then
     poolFile=${config}/${pool}
     if [ ! -f ${poolFile} ] ; then
        if [ -f ${poolFile}.poollist ] ; then
           poolFile=${poolFile}.poollist
        else
           echo "Pools file not found : ${poolFile}" 1>&2
           exit 4
        fi
     fi
     POOL="pool=${poolFile}"
     x=`echo ${pool} | awk -F. '{print $1}'`
     domainName=${x}Domain
elif [ -f ${config}/${ourBaseName}.poollist ] ; then
     POOL="pool=${poolFile}"
     domainName=${ourBaseName}Domain
else
     domainName=${ourBaseName}Domain
fi
if [ ! -z "${domain}" ] ; then
    domainName=${domain}
    base=`expr "${domainName}" : "\(.*\)Domain"`
    if [ -z "$base" ] ; then base=${domainName} ; fi
fi
if [ -z  "${logfile}" ] ; then
   if [ -z "${logArea}" ] ; then
     logfile=/tmp/${domainName}.log
   else
     logfile=${logArea}/${domainName}.log
   fi
fi
touch ${logfile} 2>/dev/null
if [ $? -ne 0 ] ; then
  echo "Could not write to logfile ${logfile}; using /dev/null" 1>&2
  logfile=/dev/null
fi

stopFile="/tmp/.dcache-stop.${domainName}"
pidFile="${pidDir}/dcache.${domainName}.pid"

#
#
procStop() {
   if [ ! -f ${pidFile} ] ; then
      echo "Cannot find appropriate pid file (${pidFile})" 1>&2
      exit 0
   fi
   x=`cat ${pidFile}`
   if [ -z "$x" ] ; then
      echo "Pid file (${pidFile}) does not contain valid PID" 1>&2
      exit 1
   fi
   touch "$stopFile" 2>/dev/null
   kill -TERM $x 1>/dev/null 2>/dev/null
   printf "Stopping ${domainName} (pid=`cat ${pidFile}`) "

   for c in  0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
     kill -0 $x 1>/dev/null 2>/dev/null
     if [ $? -ne 0 ] ; then
        rm -f $pidFile
        echo "Done"
        exit 0
     fi
     printf "$c "
     sleep 1
     if [ $c -eq 9 ] ; then
         kill -9 $x
     fi
   done
   echo "Giving up : ${domainName} might still be running" 1>&2
   exit 4

}
procStart() {

  if [ -f ${pidFile} ] ; then
     x=`cat ${pidFile}`
     kill -0 $x 1>/dev/null 2>/dev/null
     if [ $? -eq 0 ] ; then
        echo "${domainName} might still be running" 1>&2
        exit 4
     fi
  fi

  touch ${pidFile} 2>/dev/null
  if [ $? -ne 0 ] ; then
    echo "Could not use pid file ${pidFile}, cannot startup !!" 1>&2
    exit 4
  fi

  if [ ! -z "${telnetPort}" ] ; then
     TELNET_PORT="-telnet ${telnetPort}"
  elif [ ! -z "${telnet}" ] ; then
     TELNET_PORT="-telnet ${telnet}"
  fi
  if [ ! -z "${batch}" ] ; then
     batchFile=${batch}
  else
     batchFile=${config}/${ourBaseName}.batch
  fi
  if [ -f "$batchFile" ] ; then
      BATCH_FILE="-batch ${batchFile}"
  else
      echo "Batch file does not exist: ${batchFile}, cannot continue ..." 1>&2
      exit 5
  fi
  if [ ! -z "${debug}" ] ; then DEBUG="-debug" ; fi
  CLASSPATH="${classpath}:${thisDir}/../..:${thisDir}/../classes/cells.jar:${thisDir}/../classes/dcache.jar:${externalLibsClassPath}"
  LD_LIBRARY_PATH="${librarypath}"
  export LD_LIBRARY_PATH

# use local jre ( ${ourHomeDir}/jre if it exist. This will allows to
# us to package required jre with dCache.

  if [ -x "${ourHomeDir}/jre/bin/java" ]
  then
      java="${ourHomeDir}/jre/bin/java"
  fi

# we need to activate the globus gsi key pair caching for those installations that
# continue to use  dCacheSetup where -Dorg.globus.jglobus.delegation.cache.lifetime is not 
# specified
  if ! echo "${java_options}" | grep -- "-Dorg\.globus\.jglobus\.delegation\.cache\.lifetime=" 2>&1 >/dev/null ; then
     java_options="${java_options} \
                 -Dorg.globus.jglobus.delegation.cache.lifetime=30000"
  fi

# Add a java option for an endorsed directory
  java_options="${java_options} \
                -cp ${CLASSPATH} \
                -Djava.endorsed.dirs=${ourHomeDir}/classes/endorsed \
                dmg.cells.services.Domain ${domainName} $TELNET_PORT \
                -param setupFile=${setupFilePath} \
                       ourHomeDir=${ourHomeDir} \
                       ourName=${ourBaseName} \
                       ${POOL} \
                $BATCH_FILE $DEBUG"

#
#  echo "(Using classpath : $CLASSPATH )"
#  echo "(Using librarypath : $LD_LIBRARY_PATH )"
#  echo "${java} ${java_options}"
     [ "${logMode}" = "new" ] && mv -f "${logfile}" "${logfile}.old"

     if [ -f "${config}/delay" ] ; then
         delay=`cat "${config}/delay" 2>/dev/null`
     else
         delay=10
     fi

     rm -f "$stopFile"
     /bin/sh ${ourHomeDir}/share/lib/daemon ${user:+-u} "$user" -r "${stopFile}" -d "${delay}" -f -p "${pidFile}" -o "${logfile}" "${java}" ${java_options}

     printf "Starting ${domainName} "
     for c in 6 5 4 3 2 1 0 ; do
        latestPid=`cat ${pidFile} 2>/dev/null`
        kill -0 ${latestPid} 1>/dev/null 2>/dev/null
        if [ $? -eq 0 ] ; then
            break
        fi

        sleep 1
        printf "$c "
     done
     latestPid=`cat ${pidFile} 2>/dev/null`
     kill -0 ${latestPid} 1>/dev/null 2>/dev/null
     if [ $? -ne 0 ] ; then
         echo " failed"
         grep PANIC "${logfile}"
         exit 4
     else
         echo "Done (pid=${latestPid})"
     fi
  exit 0
}
procHelp() {
   echo "Usage : ${ourBaseName} start|stop"
   exit 4
}

procSwitch() {
   case "$1" in
      *start)       shift 1 ; procStart $* ;;
      *stop)        procStop  ;;
      *) procHelp $*
      ;;
   esac
}
