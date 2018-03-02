package net.something.jenkins.workflow
import com.cloudbees.groovy.cps.NonCPS

/**
 * Created by bernard on 9/10/17.
 */

class BuildConfig implements Serializable {
    static Map resolve(def body = [:]) {

        Map config = [:]

        if (body in Map) {
            config = body
        } else if (body in Closure) {
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body.delegate = config
            body()
        } else {
            throw  new Exception(sprintf("Unsupported build config type:%s", [config.getClass()]))
        }
        // Extra vars used to supply addition repo specific vars to makefile. "SPECIFIC_VAR=something SPECIFIC_VAR2=thing"
        if (!config.containsKey('EXTRA_VARS')) {
            config['EXTRA_VARS'] = ''
        }
        // Env_excludes used to redact Env var from stdout echo in pipelines. "-u SECRET_KEY -u SECRET_PASSWORD"
        if (!config.containsKey('ENV_EXCLUDES')) {
            config['ENV_EXCLUDES'] = ''
        }
        return config
    }
}