package com.emc.object.util;

import com.emc.object.s3.bean.Region;

import javax.xml.bind.annotation.adapters.XmlAdapter;

public class RegionAdapter extends XmlAdapter<String, Region> {
    @Override
    public Region unmarshal(String s) throws Exception {
        Region region = Region.fromConstraint(s);
        return region == null ? Region.valueOf(s) : region;
    }

    @Override
    public String marshal(Region o) throws Exception {
        return o.getConstraint();
    }
}
