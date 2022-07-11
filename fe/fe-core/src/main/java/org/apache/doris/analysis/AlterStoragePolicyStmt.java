package org.apache.doris.analysis;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.PrintableMap;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.policy.Policy;
import org.apache.doris.policy.PolicyTypeEnum;
import org.apache.doris.policy.StoragePolicy;
import org.apache.doris.qe.ConnectContext;

import lombok.Data;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;



@Data
public class AlterStoragePolicyStmt extends DdlStmt {
    private final String storagePolicyName;
    private final Map<String, String> properties;

    public AlterStoragePolicyStmt(String storagePolicyName, Map<String, String> properties) {
        this.storagePolicyName = storagePolicyName;
        this.properties = properties;
    }

    @Override
    public void analyze(Analyzer analyzer) throws UserException {
        super.analyze(analyzer);

        // check auth
        if (!Catalog.getCurrentCatalog().getAuth().checkGlobalPriv(ConnectContext.get(), PrivPredicate.ADMIN)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR, "ADMIN");
        }

        if (properties == null || properties.isEmpty()) {
            throw new AnalysisException("Storage policy properties can't be null");
        }

        // default storage policy use alter storage policy to add s3 resource.
        if (!storagePolicyName.equalsIgnoreCase(Config.default_storage_policy)
                && properties.containsKey(StoragePolicy.STORAGE_RESOURCE)) {
            throw new AnalysisException("not support change storage policy's storage resource"
                + ", you can change s3 properties by alter resource");
        }

        boolean hasCooldownDatetime = false;
        boolean hasCooldownTtl = false;

        if (properties.containsKey(StoragePolicy.COOLDOWN_DATETIME)) {
            hasCooldownDatetime = true;
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            try {
                df.parse(properties.get(StoragePolicy.COOLDOWN_DATETIME));
            } catch (ParseException e) {
                throw new AnalysisException(String.format("cooldown_datetime format error: %s",
                    properties.get(StoragePolicy.COOLDOWN_DATETIME)), e);
            }
        }

        if (properties.containsKey(StoragePolicy.COOLDOWN_TTL)) {
            hasCooldownTtl = true;
            if (Integer.parseInt(properties.get(StoragePolicy.COOLDOWN_TTL)) < 0) {
                throw new AnalysisException("cooldown_ttl must >= 0.");
            }
        }

        if (hasCooldownDatetime && hasCooldownTtl) {
            throw new AnalysisException(StoragePolicy.COOLDOWN_DATETIME + " and "
                + StoragePolicy.COOLDOWN_TTL + " can't be set together.");
        }
        if (!hasCooldownDatetime && !hasCooldownTtl) {
            throw new AnalysisException(StoragePolicy.COOLDOWN_DATETIME + " or "
                + StoragePolicy.COOLDOWN_TTL + " must be set");
        }

        // check resource existence
        List<Policy> policiesByType = Catalog.getCurrentCatalog()
                .getPolicyMgr().getPoliciesByType(PolicyTypeEnum.STORAGE);
        Optional<Policy> hasPolicy = policiesByType.stream()
                .filter(policy -> policy.getPolicyName().equals(this.storagePolicyName)).findAny();
        if (!hasPolicy.isPresent()) {
            throw new AnalysisException("Unknown storage policy: " + this.storagePolicyName);
        }

        StoragePolicy storagePolicy = (StoragePolicy) hasPolicy.get();

        do {
            if (storagePolicyName.equalsIgnoreCase(Config.default_storage_policy)) {
                // default storage policy
                if (storagePolicy.getStorageResource() != null && hasCooldownDatetime) {
                    // alter cooldown datetime, can do
                    break;
                }

                if (storagePolicy.getStorageResource() != null && hasCooldownTtl) {
                    // alter cooldown ttl, can do
                    break;
                }

                if (storagePolicy.getStorageResource() == null) {
                    // alter add s3 resource, can do, check must have ttl or datetime.
                    if (hasCooldownTtl == false && hasCooldownDatetime == false) {
                        throw new AnalysisException("please alter default policy to add s3 , ttl or datetime.");
                    }
                    break;
                }
                throw new AnalysisException("default storage policy has been set s3 Resource.");
            }
        } while (false);

        // check properties
        storagePolicy.checkProperties(properties);
    }

    @Override
    public String toSql() {
        StringBuffer sb = new StringBuffer();
        sb.append("ALTER STORAGE POLICY '").append(storagePolicyName).append("' ");
        sb.append("PROPERTIES(").append(new PrintableMap<>(properties, " = ", true, false)).append(")");
        return sb.toString();
    }

}
