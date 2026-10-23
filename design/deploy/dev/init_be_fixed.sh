#!/bin/bash
set -eo pipefail

curdir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

MACHINE_OS=$(uname -s)
if [[ "$(uname -s)" == 'Darwin' ]] && command -v brew &>/dev/null; then
    PATH="$(brew --prefix)/opt/gnu-getopt/bin:${PATH}"
    export PATH
fi

OPTS="$(getopt \
    -n "$0" \
    -o '' \
    -l 'daemon' \
    -l 'console' \
    -- "$@")"

eval set -- "${OPTS}"

RUN_DAEMON=0
RUN_CONSOLE=0
while true; do
    case "$1" in
    --daemon)
        RUN_DAEMON=1
        shift
        ;;
    --console)
        RUN_CONSOLE=1
        shift
        ;;
    --)
        shift
        break
        ;;
    *)
        echo "Internal error"
        exit 1
        ;;
    esac
done

DORIS_HOME="$(
    cd "${curdir}/.."
    pwd
)"
export DORIS_HOME

# Disabled system checks for Docker environment
# if [[ "$(uname -s)" != 'Darwin' ]]; then
#     MAX_MAP_COUNT="$(cat /proc/sys/vm/max_map_count)"
#     if [[ "${MAX_MAP_COUNT}" -lt 2000000 ]]; then
#         echo "Please set vm.max_map_count to be 2000000 under root using 'sysctl -w vm.max_map_count=2000000'."
#         exit 1
#     fi
#
#     if [[ "$(swapon -s | wc -l)" -gt 1 ]]; then
#         echo "Please disable swap memory before installation."
#         exit 1
#     fi
# fi

MAX_FILE_COUNT="$(ulimit -n)"
if [[ "${MAX_FILE_COUNT}" -lt 60000 ]]; then
    echo "Set max number of open file descriptors to a value greater than 60000."
    echo "Ask your system manager to modify /etc/security/limits.conf and append content like"
    echo "  * soft nofile 655350"
    echo "  * hard nofile 655350"
    echo "and then run 'ulimit -n 655350' to take effect on current session."
    exit 1
fi

# add java libs
# Must add hadoop libs, because we should load specified jars
# instead of jars in hadoop libs, such as avro
preload_jars=("preload-extensions")
preload_jars+=("java-udf")

DORIS_PRELOAD_JAR=
for preload_jar_dir in "${preload_jars[@]}"; do
    for f in "${DORIS_HOME}/lib/java_extensions/${preload_jar_dir}"/*.jar; do
        if [[ "${f}" == *"preload-extensions-project.jar" ]]; then
            DORIS_PRELOAD_JAR="${f}"
            continue
        elif [[ -z "${DORIS_CLASSPATH}" ]]; then
            export DORIS_CLASSPATH="${f}"
        else
            export DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
        fi
    done
done

if [[ -d "${DORIS_HOME}/lib/hadoop_hdfs/" ]]; then
    # add hadoop libs
    for f in "${DORIS_HOME}/lib/hadoop_hdfs/common"/*.jar; do
        DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
    done
    for f in "${DORIS_HOME}/lib/hadoop_hdfs/common/lib"/*.jar; do
        DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
    done
    for f in "${DORIS_HOME}/lib/hadoop_hdfs/hdfs"/*.jar; do
        DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
    done
    for f in "${DORIS_HOME}/lib/hadoop_hdfs/hdfs/lib"/*.jar; do
        DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
    done
fi

# add custom_libs to CLASSPATH
if [[ -d "${DORIS_HOME}/custom_lib" ]]; then
    for f in "${DORIS_HOME}/custom_lib"/*.jar; do
        DORIS_CLASSPATH="${DORIS_CLASSPATH}:${f}"
    done
fi

# make sure the preload-extensions-project.jar is at first order, so that some classed
# with same qualified name can be loaded priority from preload-extensions-project.jar.
DORIS_CLASSPATH="${DORIS_PRELOAD_JAR}:${DORIS_CLASSPATH}"

if [[ -n "${HADOOP_CONF_DIR}" ]]; then
    export DORIS_CLASSPATH="${DORIS_CLASSPATH}:${HADOOP_CONF_DIR}"
fi

# the CLASSPATH and LIBHDFS_OPTS is used for hadoop libhdfs
# and conf/ dir so that hadoop libhdfs can read .xml config file in conf/
export CLASSPATH="${DORIS_HOME}/conf/:${DORIS_CLASSPATH}:${CLASSPATH}"
# DORIS_CLASSPATH is for self-managed jni
export DORIS_CLASSPATH="-Djava.class.path=${DORIS_CLASSPATH}"

# log ${DORIS_CLASSPATH}

export LD_LIBRARY_PATH="${DORIS_HOME}/lib/hadoop_hdfs/native:${LD_LIBRARY_PATH}"

jdk_version() {
    local java_cmd="${1}"
    local result
    local IFS=$'\n'

    if ! command -v "${java_cmd}" >/dev/null; then
        echo "ERROR: invalid java_cmd ${java_cmd}" >>"${LOG_DIR}/be.out"
        result=no_java
        return 1
    else
        echo "INFO: java_cmd ${java_cmd}" >>"${LOG_DIR}/be.out"
        local version
        # remove \r for Cygwin
        version="$("${java_cmd}" -Xms32M -Xmx32M -version 2>&1 | tr '\r' '\n' | grep version | awk '{print $3}')"
        version="${version//\"/}"
        if [[ "${version}" =~ ^1\. ]]; then
            result="$(echo "${version}" | awk -F '.' '{print $2}')"
        else
            result="$(echo "${version}" | awk -F '.' '{print $1}')"
        fi
        echo "INFO: jdk_version ${result}" >>"${LOG_DIR}/be.out"
    fi
    echo "${result}"
    return 0
fi

# export env variables from be.conf
#
# LOG_DIR
# PID_DIR
export LOG_DIR="${DORIS_HOME}/log"
PID_DIR="$(cd "${curdir}" && pwd)"
export PID_DIR

while read -r line; do
    envline="$(echo "${line}" |
        sed 's/[[:blank:]]*=[[:blank:]]*/=/g' |
        sed 's/^[[:blank:]]*//g' |
        grep -E "^[[:upper:]]([[:upper:]]|_|[[:digit:]])*=" ||
        true)"
    envline="$(eval "echo ${envline}")"
    if [[ "${envline}" == *"="* ]]; then
        eval 'export "${envline}"'
    fi
done <"${DORIS_HOME}/conf/be.conf"

STDOUT_LOGGER="${LOG_DIR}/be.out"
if [ ${RUN_DAEMON} -eq 1 ]; then
    nohup java ${JAVA_OPTS} ${DORIS_CLASSPATH} -cp "${CLASSPATH}" org.apache.doris.DorisBE >>"${STDOUT_LOGGER}" 2>&1 &
    echo $! >"${PID_DIR}/be.pid"
elif [ ${RUN_CONSOLE} -eq 1 ]; then
    java ${JAVA_OPTS} ${DORIS_CLASSPATH} -cp "${CLASSPATH}" org.apache.doris.DorisBE
else
    echo "Please specify --daemon or --console"
    exit 1
fi